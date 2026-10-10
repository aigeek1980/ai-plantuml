package com.aiplantuml.play;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Turns the frames recorded while a run was played into something that can be shared:
 * an animated GIF, or the frames as separate images.
 * <p>
 * The frames are pictures of the player itself, taken as each step was drawn, so what
 * comes out is what was watched - the current call in red, the lifelines it runs between
 * picked out, and nothing of the steps still to come.
 * <p>
 * Frames are still laid out on a shared canvas the size of the largest: the window can be
 * resized part way through a run, and a GIF takes its size from its first frame.
 */
public final class RunExporter {

    public enum Format { GIF, PNG_FRAMES }

    /**
     * @param scale          1.0 for the frames' own size; less to keep a long run's file down
     * @param secondsPerStep how long each frame is held
     */
    public record Options(Format format, double scale, double secondsPerStep) { }

    /** Told how far the export has got, and asked whether to carry on. */
    public interface Progress {
        void rendered(int done, int total);

        boolean cancelled();
    }

    /** GIF holds its delays in hundredths of a second, and will not sit still for 0. */
    private static final int MIN_DELAY_HUNDREDTHS = 2;
    /** The last frame is held longer, so a run does not snap back the instant it finishes. */
    private static final int END_HOLD_FACTOR = 4;

    private RunExporter() {
    }

    /** What came out, so the export can say so rather than just claiming success. */
    public record Result(int frames, int width, int height) { }

    /**
     * @param frames one PNG per step, recorded as the run was played
     * @param target the .gif to write, or the directory to fill with frames
     */
    public static Result export(List<Path> frames, Path target, Options options, Progress progress)
            throws IOException {
        if (frames.isEmpty()) return new Result(0, 0, 0);

        int[] canvas = measure(frames);
        int written = options.format() == Format.GIF
                ? writeGif(frames, canvas, target, options, progress)
                : writeFrames(frames, canvas, target, options, progress);
        return new Result(written,
                (int) Math.round(canvas[0] * options.scale()),
                (int) Math.round(canvas[1] * options.scale()));
    }

    /** Reads just the headers, so the canvas is known without decoding anything. */
    private static int[] measure(List<Path> frames) throws IOException {
        int width = 0;
        int height = 0;
        for (Path frame : frames) {
            int[] size = readSize(frame);
            width = Math.max(width, size[0]);
            height = Math.max(height, size[1]);
        }
        return new int[] { width, height };
    }

    private static int writeFrames(List<Path> frames, int[] canvas, Path directory,
                                   Options options, Progress progress) throws IOException {
        Files.createDirectories(directory);
        int written = 0;
        for (Path frame : frames) {
            if (progress.cancelled()) break;
            BufferedImage image = compose(frame, canvas, options.scale());
            ImageIO.write(image, "png", directory.resolve(name(written)).toFile());
            written++;
            progress.rendered(written, frames.size());
        }
        if (written > 0) writeVideoNote(directory, options.secondsPerStep());
        return written;
    }

    /**
     * Frames are a step away from a video, but the encoder is not ours to ship, so the
     * command that turns them into one is left beside them for anyone who has ffmpeg.
     */
    private static void writeVideoNote(Path directory, double secondsPerStep) throws IOException {
        double fps = Math.max(0.1, 1.0 / Math.max(0.05, secondsPerStep));
        String note = """
                These are the frames of one run of the diagram, in order.

                To make a video from them, with ffmpeg on your PATH, run this in
                this folder:

                  ffmpeg -framerate %.2f -i step-%%04d.png -c:v libx264 -pix_fmt yuv420p \
                -vf "pad=ceil(iw/2)*2:ceil(ih/2)*2" run.mp4

                The frame rate above matches the speed the run was played at. Thin
                diagram text survives better at a larger size, so export at 100%% if
                the video is going to be watched full screen.
                """.formatted(fps);
        Files.writeString(directory.resolve("README.txt"), note);
    }

    private static int writeGif(List<Path> frames, int[] canvas, Path target,
                                Options options, Progress progress) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
        if (!writers.hasNext()) throw new IOException("No GIF encoder is available");
        ImageWriter writer = writers.next();

        int delay = Math.max(MIN_DELAY_HUNDREDTHS, (int) Math.round(options.secondsPerStep() * 100));
        int written = 0;

        try (OutputStream out = Files.newOutputStream(target);
             ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);

            for (int i = 0; i < frames.size(); i++) {
                if (progress.cancelled()) break;
                BufferedImage frame = compose(frames.get(i), canvas, options.scale());
                ImageWriteParam params = writer.getDefaultWriteParam();
                IIOMetadata metadata = writer.getDefaultImageMetadata(
                        new ImageTypeSpecifier(frame), params);
                int hold = i == frames.size() - 1 ? delay * END_HOLD_FACTOR : delay;
                writer.writeToSequence(
                        new IIOImage(frame, null, timed(metadata, hold, i == 0)), params);
                written++;
                progress.rendered(written, frames.size());
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return written;
    }

    /**
     * Sets a frame's delay, and on the first frame the block that makes players loop.
     * GIF carries both as metadata trees rather than as anything the writer takes
     * directly, so they have to be grafted on frame by frame.
     */
    private static IIOMetadata timed(IIOMetadata metadata, int delayHundredths, boolean first)
            throws IOException {
        String format = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);

        IIOMetadataNode control = child(root, "GraphicControlExtension");
        control.setAttribute("disposalMethod", "none");
        control.setAttribute("userInputFlag", "FALSE");
        control.setAttribute("transparentColorFlag", "FALSE");
        control.setAttribute("transparentColorIndex", "0");
        control.setAttribute("delayTime", String.valueOf(delayHundredths));

        if (first) {
            IIOMetadataNode extensions = child(root, "ApplicationExtensions");
            IIOMetadataNode loop = new IIOMetadataNode("ApplicationExtension");
            loop.setAttribute("applicationID", "NETSCAPE");
            loop.setAttribute("authenticationCode", "2.0");
            loop.setUserObject(new byte[] { 1, 0, 0 });      // repeat endlessly
            extensions.appendChild(loop);
        }

        try {
            metadata.setFromTree(format, root);
        } catch (Exception e) {
            throw new IOException("The GIF frame's timing could not be set", e);
        }
        return metadata;
    }

    private static IIOMetadataNode child(IIOMetadataNode parent, String name) {
        for (int i = 0; i < parent.getLength(); i++) {
            if (parent.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) parent.item(i);
            }
        }
        IIOMetadataNode added = new IIOMetadataNode(name);
        parent.appendChild(added);
        return added;
    }

    /**
     * Lays a frame on the shared canvas, top left, and scales the result. The padding
     * takes its colour from the frame itself, so a diagram drawn on anything other than
     * white does not end up in a white box.
     */
    private static BufferedImage compose(Path file, int[] canvas, double scale) throws IOException {
        BufferedImage source = ImageIO.read(file.toFile());
        if (source == null) throw new IOException("Frame " + file.getFileName() + " could not be read");

        int width = Math.max(1, (int) Math.round(canvas[0] * scale));
        int height = Math.max(1, (int) Math.round(canvas[1] * scale));

        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(new Color(source.getRGB(0, 0)));
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0,
                    Math.max(1, (int) Math.round(source.getWidth() * scale)),
                    Math.max(1, (int) Math.round(source.getHeight() * scale)), null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Reads just the header, so the canvas can be sized without decoding every frame. */
    private static int[] readSize(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IOException("Unreadable frame: " + file.getFileName());
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new int[] { reader.getWidth(0), reader.getHeight(0) };
            } finally {
                reader.dispose();
            }
        }
    }

    private static String name(int index) {
        return String.format("step-%04d.png", index + 1);
    }

    /** Clears a folder of recorded frames, which is nobody's to keep once the run is over. */
    public static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover temp file is not worth failing an export that worked.
                }
            });
        } catch (IOException ignored) {
            // Likewise.
        }
    }
}

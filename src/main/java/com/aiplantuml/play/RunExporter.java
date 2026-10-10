package com.aiplantuml.play;

import com.aiplantuml.render.PlantUmlRenderer;

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
 * Turns a played run into something that can be shared: an animated GIF, or the frames
 * as separate images.
 * <p>
 * Frames are rendered from each step's own PlantUML source rather than captured from the
 * window, so what comes out is the full diagram at its own resolution however the player
 * happened to be scrolled or zoomed.
 * <p>
 * A build-up run grows as it goes, so every frame is laid out on a canvas the size of the
 * largest one. Without that a GIF would take its size from the first frame and clip the
 * rest of the run to the two lifelines it started with.
 */
public final class RunExporter {

    public enum Format { GIF, PNG_FRAMES }

    /**
     * @param scale          1.0 for the diagram's own size; less to keep a long run's file down
     * @param secondsPerStep how long each frame is held, taken from the player's speed
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
     * @param sources one PlantUML source per step, in the order they were played
     * @param target  the .gif to write, or the directory to fill with frames
     */
    public static Result export(List<String> sources, Path target, Options options, Progress progress)
            throws IOException {
        if (sources.isEmpty()) return new Result(0, 0, 0);

        Path work = Files.createTempDirectory("aiplantuml-export");
        try {
            int[] canvas = renderFrames(sources, work, progress);
            if (canvas == null) return new Result(0, 0, 0);     // cancelled part way
            int frames = options.format() == Format.GIF
                    ? writeGif(sources.size(), work, canvas, target, options, progress)
                    : writeFrames(sources.size(), work, canvas, target, options, progress);
            return new Result(frames,
                    (int) Math.round(canvas[0] * options.scale()),
                    (int) Math.round(canvas[1] * options.scale()));
        } finally {
            deleteTree(work);
        }
    }

    /**
     * Renders every step to its own file rather than holding them in memory: a long run
     * at a diagram's full size is far more pixels than a heap wants to carry at once.
     *
     * @return the canvas every frame will be laid on, or null if the export was cancelled
     */
    private static int[] renderFrames(List<String> sources, Path work, Progress progress)
            throws IOException {
        PlantUmlRenderer renderer = new PlantUmlRenderer();
        int width = 0;
        int height = 0;

        for (int i = 0; i < sources.size(); i++) {
            if (progress.cancelled()) return null;
            PlantUmlRenderer.PngRenderResult result = renderer.renderPng(sources.get(i));
            if (result.isError()) {
                throw new IOException("Step " + (i + 1) + " could not be drawn: " + result.errorText());
            }
            Path file = work.resolve(name(i));
            Files.write(file, result.png());

            int[] size = readSize(file);
            width = Math.max(width, size[0]);
            height = Math.max(height, size[1]);
            progress.rendered(i + 1, sources.size());
        }
        return new int[] { width, height };
    }

    private static int writeFrames(int count, Path work, int[] canvas, Path directory,
                                   Options options, Progress progress) throws IOException {
        Files.createDirectories(directory);
        int written = 0;
        for (int i = 0; i < count; i++) {
            if (progress.cancelled()) break;
            BufferedImage frame = compose(work.resolve(name(i)), canvas, options.scale());
            ImageIO.write(frame, "png", directory.resolve(name(i)).toFile());
            written++;
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

    private static int writeGif(int count, Path work, int[] canvas, Path target,
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

            for (int i = 0; i < count; i++) {
                if (progress.cancelled()) break;
                BufferedImage frame = compose(work.resolve(name(i)), canvas, options.scale());
                ImageWriteParam params = writer.getDefaultWriteParam();
                IIOMetadata metadata = writer.getDefaultImageMetadata(
                        new ImageTypeSpecifier(frame), params);
                int hold = i == count - 1 ? delay * END_HOLD_FACTOR : delay;
                writer.writeToSequence(
                        new IIOImage(frame, null, timed(metadata, hold, i == 0)), params);
                written++;
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

    private static void deleteTree(Path root) {
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

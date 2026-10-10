package com.aiplantuml.play;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks a {@link ScriptModel} one statement at a time, building up the trace of what
 * actually ran. Branches not chosen are never emitted at all, so they take up no room
 * in the drawing, and a loop body is emitted once per iteration asked for.
 * <p>
 * Choices are not decided here: the caller is asked, so it can put the question to the
 * user and keep the walk going afterwards.
 */
public final class TraceRun {

    /** Asked when the walk reaches a block that needs a decision. */
    public interface Decider {
        /** @return index of the branch to follow, or -1 to stop the run */
        int chooseBranch(ScriptModel.Block block);

        /** @return how many times to run the loop body, or -1 to stop the run */
        int chooseIterations(ScriptModel.Block block);
    }

    /** One position in the walk: a list of statements and how far through it we are. */
    private record Frame(List<ScriptModel.Node> nodes, int index, int repeatsLeft, ScriptModel.Block loop) {
    }

    private final ScriptModel script;
    private final Decider decider;

    private final List<String> emitted = new ArrayList<>();
    private final Set<String> onStage = new LinkedHashSet<>();
    private final Deque<Frame> stack = new ArrayDeque<>();
    private ScriptModel.Line lastStatement;

    public TraceRun(ScriptModel script, Decider decider) {
        this.script = script;
        this.decider = decider;
        reset();
    }

    public void reset() {
        emitted.clear();
        onStage.clear();
        stack.clear();
        lastStatement = null;
        stack.push(new Frame(script.body(), 0, 0, null));
    }

    /**
     * Runs statements until one is drawn, which may mean passing through the end of a
     * block or asking a question on the way.
     *
     * @return false when the run is over or the user declined a choice
     */
    public boolean step() {
        while (!stack.isEmpty()) {
            Frame frame = stack.pop();

            if (frame.index() >= frame.nodes().size()) {
                // A loop body that still owes iterations starts again rather than ending.
                if (frame.loop() != null && frame.repeatsLeft() > 1) {
                    stack.push(new Frame(frame.nodes(), 0, frame.repeatsLeft() - 1, frame.loop()));
                    continue;
                }
                continue;
            }

            ScriptModel.Node node = frame.nodes().get(frame.index());
            stack.push(new Frame(frame.nodes(), frame.index() + 1, frame.repeatsLeft(), frame.loop()));

            if (node instanceof ScriptModel.Line line) {
                emitted.add(line.text());
                onStage.addAll(line.aliases());
                lastStatement = line;
                return true;
            }

            ScriptModel.Block block = (ScriptModel.Block) node;
            switch (block.kind()) {
                case "alt", "opt" -> {
                    int branch = decider.chooseBranch(block);
                    if (branch < 0) { stack.push(frame); return false; }
                    if (branch < block.branches().size()) {
                        stack.push(new Frame(block.branches().get(branch).body(), 0, 0, null));
                    }
                }
                case "loop" -> {
                    int times = decider.chooseIterations(block);
                    if (times < 0) { stack.push(frame); return false; }
                    if (times > 0 && !block.branches().isEmpty()) {
                        stack.push(new Frame(block.branches().get(0).body(), 0, times, block));
                    }
                }
                // par, group and critical have nothing to decide: run what is inside.
                default -> {
                    for (int b = block.branches().size() - 1; b >= 0; b--) {
                        stack.push(new Frame(block.branches().get(b).body(), 0, 0, null));
                    }
                }
            }
        }
        return false;
    }

    public String currentSource() {
        return script.toSource(emitted, onStage);
    }

    public int stepsRun() {
        return emitted.size();
    }

    /** Source line of the statement just drawn, for following along in the editor. */
    public int lastSourceLine() {
        return lastStatement == null ? -1 : lastStatement.sourceLine();
    }

    public boolean hasRunAnything() {
        return !emitted.isEmpty();
    }
}

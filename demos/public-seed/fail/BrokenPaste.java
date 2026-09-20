package demo;

public final class BrokenPaste {
    // Agent paste artifacts — should trip invariant on added lines
    <<<<<<< HEAD
    public void broken() {
        // ... existing code ...
    }
    =======
    public void broken() {
        System.out.println("paste");
    }
    >>>>>>> feature
}

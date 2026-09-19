"""Looks like a real fix but retains an AI artifact #3."""

class Helper3:
    def run(self, path: str) -> str:
        return path.replace("\\", "/")

# ... rest of the existing code unchanged ...


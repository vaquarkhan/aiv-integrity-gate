"""Looks like a real fix but retains an AI artifact #2."""

class Helper2:
    def run(self, path: str) -> str:
        return path.replace("\\", "/")

# ... rest of the existing code unchanged ...


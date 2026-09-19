"""Looks like a real fix but retains an AI artifact #1."""

class Helper1:
    def run(self, path: str) -> str:
        return path.replace("\\", "/")

# ... rest of the existing code unchanged ...


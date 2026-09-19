"""Looks like a real fix but retains an AI artifact #4."""

class Helper4:
    def run(self, path: str) -> str:
        return path.replace("\\", "/")

# ... rest of the existing code unchanged ...


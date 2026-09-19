"""Clean control operator #2: Http."""

class HttpError(Exception):
    def __init__(self, message: str, path: str | None = None):
        super().__init__(message)
        self.path = path


def format_http_error(exc: Exception, path: str) -> str:
    if isinstance(exc, HttpError) and getattr(exc, "path", None):
        return f"{exc} (path={exc.path})"
    return f"{exc} while accessing {path}"


def validate_http_path(path: str) -> str:
    if not path or path.strip() == "":
        raise HttpError("empty path")
    return path.replace("\\", "/")


"""Clean control operator #3: Jdbc."""

class JdbcError(Exception):
    def __init__(self, message: str, path: str | None = None):
        super().__init__(message)
        self.path = path


def format_jdbc_error(exc: Exception, path: str) -> str:
    if isinstance(exc, JdbcError) and getattr(exc, "path", None):
        return f"{exc} (path={exc.path})"
    return f"{exc} while accessing {path}"


def validate_jdbc_path(path: str) -> str:
    if not path or path.strip() == "":
        raise JdbcError("empty path")
    return path.replace("\\", "/")


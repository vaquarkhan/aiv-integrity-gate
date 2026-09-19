"""Clean control operator #5: Redis."""

class RedisError(Exception):
    def __init__(self, message: str, path: str | None = None):
        super().__init__(message)
        self.path = path


def format_redis_error(exc: Exception, path: str) -> str:
    if isinstance(exc, RedisError) and getattr(exc, "path", None):
        return f"{exc} (path={exc.path})"
    return f"{exc} while accessing {path}"


def validate_redis_path(path: str) -> str:
    if not path or path.strip() == "":
        raise RedisError("empty path")
    return path.replace("\\", "/")


"""Clean control operator #1: Sftp."""

class SftpError(Exception):
    def __init__(self, message: str, path: str | None = None):
        super().__init__(message)
        self.path = path


def format_sftp_error(exc: Exception, path: str) -> str:
    if isinstance(exc, SftpError) and getattr(exc, "path", None):
        return f"{exc} (path={exc.path})"
    return f"{exc} while accessing {path}"


def validate_sftp_path(path: str) -> str:
    if not path or path.strip() == "":
        raise SftpError("empty path")
    return path.replace("\\", "/")


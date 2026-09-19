"""Clean sample resembling a small real provider fix (control)."""


class SFTPException(Exception):
    """Raised when an SFTP operation fails."""

    def __init__(self, message: str, path: str | None = None):
        super().__init__(message)
        self.path = path


def format_sftp_error(exc: Exception, path: str) -> str:
    if isinstance(exc, SFTPException) and exc.path:
        return f"{exc} (path={exc.path})"
    return f"{exc} while accessing {path}"

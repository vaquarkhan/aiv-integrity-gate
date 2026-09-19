"""Clean control operator #4: Kafka."""

class KafkaError(Exception):
    def __init__(self, message: str, path: str | None = None):
        super().__init__(message)
        self.path = path


def format_kafka_error(exc: Exception, path: str) -> str:
    if isinstance(exc, KafkaError) and getattr(exc, "path", None):
        return f"{exc} (path={exc.path})"
    return f"{exc} while accessing {path}"


def validate_kafka_path(path: str) -> str:
    if not path or path.strip() == "":
        raise KafkaError("empty path")
    return path.replace("\\", "/")


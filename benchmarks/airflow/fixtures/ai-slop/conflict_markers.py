"""Synthetic fixture with unresolved merge conflict markers."""

<<<<<<< HEAD
def parse_conn(uri: str) -> str:
    return uri
=======
def parse_conn(uri: str) -> dict:
    return {"uri": uri}
>>>>>>> branch

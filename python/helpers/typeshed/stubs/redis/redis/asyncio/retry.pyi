from collections.abc import Callable
from typing import Any, Awaitable, TypeVar

from redis.backoff import AbstractBackoff
from redis.exceptions import RedisError

_T = TypeVar("_T")

class Retry:
    def __init__(self, backoff: AbstractBackoff, retries: int, supported_errors: tuple[type[RedisError], ...] = ...) -> None: ...
    async def call_with_retry(self, do: Callable[[], Awaitable[_T]], fail: Callable[[RedisError], Any]) -> _T: ...

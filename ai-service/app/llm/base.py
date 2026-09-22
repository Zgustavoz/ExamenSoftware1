"""Interfaz del proveedor LLM (intercambiable) y errores del dominio."""

from typing import Protocol


class LlmError(Exception):
    """Error base del motor de IA."""


class LlmTimeout(LlmError):
    """El proveedor no respondió a tiempo → HTTP 504."""


class LlmUnavailable(LlmError):
    """El proveedor está caído, mal configurado o rechazó la petición → HTTP 503."""


class InvalidLlmResponse(LlmError):
    """El proveedor respondió, pero no con el JSON esperado (tras el reintento) → HTTP 502."""


class LlmProvider(Protocol):
    def complete(self, system: str, messages: list[dict]) -> str:
        """Devuelve el texto de la respuesta. `messages` = [{"role": "user"|"assistant", "content": str}]."""
        ...

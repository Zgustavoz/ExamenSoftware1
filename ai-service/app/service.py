"""Lógica del Copilot: arma los prompts, llama al proveedor y valida la salida (con un reintento)."""

import json
import logging
import re
from typing import TypeVar

from pydantic import BaseModel, ValidationError

from .llm import InvalidLlmResponse, LlmProvider
from .prompts import (
    COMMAND_SYSTEM,
    INTERPRET_SYSTEM,
    SEQUENCE_SYSTEM,
    command_user_message,
    interpret_user_message,
    sequence_user_message,
)
from .schemas import (
    CommandRequest,
    CommandResponse,
    InterpretRequest,
    InterpretResponse,
    SequenceRequest,
    SequenceResponse,
)

log = logging.getLogger(__name__)
M = TypeVar("M", bound=BaseModel)

_FENCE = re.compile(r"^```(?:json)?\s*|\s*```$", re.IGNORECASE)
RETRY_HINT = (
    "Tu respuesta anterior no era un JSON válido con el esquema indicado. "
    "Responde ÚNICAMENTE con el objeto JSON correcto, sin texto adicional."
)


def extract_json(text: str) -> dict:
    """Extrae el objeto JSON de la respuesta aunque venga dentro de un bloque markdown o con texto alrededor."""
    cleaned = _FENCE.sub("", text.strip())
    start, end = cleaned.find("{"), cleaned.rfind("}")
    if start == -1 or end <= start:
        raise ValueError("no hay un objeto JSON en la respuesta")
    data = json.loads(cleaned[start : end + 1])
    if not isinstance(data, dict):
        raise ValueError("la respuesta no es un objeto JSON")
    return data


class CopilotService:
    def __init__(self, provider: LlmProvider) -> None:
        self.provider = provider

    def interpret(self, req: InterpretRequest) -> InterpretResponse:
        messages = [{"role": h.role, "content": h.content} for h in req.history]
        messages.append({"role": "user", "content": interpret_user_message(req.instruction, req.contentJson)})
        return self._complete_validated(INTERPRET_SYSTEM, messages, InterpretResponse)

    def sequence(self, req: SequenceRequest) -> SequenceResponse:
        messages = [{"role": "user", "content": sequence_user_message(req.contentJson)}]
        return self._complete_validated(SEQUENCE_SYSTEM, messages, SequenceResponse)

    def command(self, req: CommandRequest) -> CommandResponse:
        """Traduce una orden hablada a la llamada HTTP que la cumple (demo del backend generado)."""
        entities = [e.model_dump() for e in req.entities]
        messages = [{"role": "user", "content": command_user_message(req.instruction, entities)}]
        return self._complete_validated(COMMAND_SYSTEM, messages, CommandResponse)

    def _complete_validated(self, system: str, messages: list[dict], model: type[M]) -> M:
        """Un reintento si el JSON es inválido; si sigue inválido → InvalidLlmResponse (HTTP 502).

        Los errores de proveedor (LlmTimeout / LlmUnavailable) se propagan sin reintentar.
        """
        attempt_messages = messages
        for attempt in (1, 2):
            text = self.provider.complete(system, attempt_messages)
            try:
                return model.model_validate(extract_json(text))
            except (ValueError, ValidationError):
                # No se registra el contenido (puede incluir datos del usuario), solo el hecho.
                log.warning("Respuesta del LLM inválida (intento %d/2)", attempt)
                attempt_messages = [
                    *messages,
                    {"role": "assistant", "content": text[:2000]},
                    {"role": "user", "content": RETRY_HINT},
                ]
        raise InvalidLlmResponse("El LLM no devolvió un JSON válido")

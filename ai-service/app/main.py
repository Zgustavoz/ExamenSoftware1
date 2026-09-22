"""API interna del Copilot IA. NO se publica por Nginx: solo la consume el backend por la red de Docker."""

import logging
import secrets
from functools import lru_cache

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse

from .config import Settings, get_settings
from .llm import InvalidLlmResponse, LlmTimeout, LlmUnavailable, build_provider
from .schemas import (
    CommandRequest,
    CommandResponse,
    InterpretRequest,
    InterpretResponse,
    SequenceRequest,
    SequenceResponse,
)
from .service import CopilotService

logging.basicConfig(level=get_settings().log_level.upper())

# Sin documentación interactiva: es un servicio interno.
app = FastAPI(title="Copilot IA", version="1.0.0", docs_url=None, redoc_url=None, openapi_url=None)


@lru_cache
def _service() -> CopilotService:
    return CopilotService(build_provider(get_settings()))


def get_service() -> CopilotService:
    return _service()


def require_internal_key(
    x_internal_key: str = Header(default=""), settings: Settings = Depends(get_settings)
) -> None:
    """Autenticación por cabecera X-Internal-Key. Sin clave configurada se rechaza todo (falla cerrado)."""
    expected = settings.ai_internal_key
    if not expected or not secrets.compare_digest(x_internal_key.encode(), expected.encode()):
        raise HTTPException(status_code=401, detail="Clave interna inválida")


def _error(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={"code": code, "message": message})


@app.exception_handler(LlmTimeout)
async def _timeout(_: Request, __: LlmTimeout) -> JSONResponse:
    return _error(504, "AI_TIMEOUT", "El proveedor LLM no respondió a tiempo")


@app.exception_handler(LlmUnavailable)
async def _unavailable(_: Request, __: LlmUnavailable) -> JSONResponse:
    return _error(503, "AI_UNAVAILABLE", "El proveedor LLM no está disponible")


@app.exception_handler(InvalidLlmResponse)
async def _invalid(_: Request, __: InvalidLlmResponse) -> JSONResponse:
    return _error(502, "AI_INVALID_RESPONSE", "El proveedor LLM no devolvió una respuesta válida")


@app.exception_handler(HTTPException)
async def _http(_: Request, exc: HTTPException) -> JSONResponse:
    return _error(exc.status_code, "UNAUTHORIZED" if exc.status_code == 401 else "ERROR", str(exc.detail))


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post(
    "/v1/interpret",
    response_model=InterpretResponse,
    response_model_exclude_none=True,
    dependencies=[Depends(require_internal_key)],
)
def interpret(req: InterpretRequest, service: CopilotService = Depends(get_service)) -> InterpretResponse:
    return service.interpret(req)


@app.post(
    "/v1/sequence",
    response_model=SequenceResponse,
    response_model_exclude_none=False,
    dependencies=[Depends(require_internal_key)],
)
def sequence(req: SequenceRequest, service: CopilotService = Depends(get_service)) -> SequenceResponse:
    return service.sequence(req)


@app.post(
    "/v1/command",
    response_model=CommandResponse,
    response_model_exclude_none=True,
    dependencies=[Depends(require_internal_key)],
)
def command(req: CommandRequest, service: CopilotService = Depends(get_service)) -> CommandResponse:
    return service.command(req)

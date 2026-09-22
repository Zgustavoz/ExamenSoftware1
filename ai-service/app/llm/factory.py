"""Selección del proveedor según LLM_PROVIDER (openai | local). No se fijan nombres de modelo en el código."""

from ..config import Settings
from .base import LlmProvider
from .openai_compatible import OpenAICompatibleProvider

OPENAI_DEFAULT_URL = "https://api.openai.com/v1"
LOCAL_DEFAULT_URL = "http://localhost:11434/v1"


def build_provider(settings: Settings) -> LlmProvider:
    if settings.llm_provider == "local":
        return OpenAICompatibleProvider(
            base_url=settings.llm_base_url or LOCAL_DEFAULT_URL,
            model=settings.llm_model,
            api_key=settings.llm_api_key,
            timeout=settings.llm_timeout_seconds,
            json_mode=False,  # no todos los servidores locales soportan response_format
        )
    return OpenAICompatibleProvider(
        base_url=settings.llm_base_url or OPENAI_DEFAULT_URL,
        model=settings.llm_model,
        api_key=settings.llm_api_key,
        timeout=settings.llm_timeout_seconds,
        json_mode=True,
    )

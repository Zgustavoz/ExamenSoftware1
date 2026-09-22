"""Configuración del servicio. Todo proviene de variables de entorno o del archivo .env de la raíz del repo."""

from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    # Los archivos se listan de menor a mayor prioridad: ../.env (raíz del repo) y luego .env local.
    # Las variables de entorno reales siempre ganan.
    model_config = SettingsConfigDict(
        env_file=("../.env", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    ai_internal_key: str = ""
    llm_provider: Literal["openai", "local"] = "openai"
    llm_api_key: str = ""
    llm_model: str = ""
    llm_base_url: str = ""
    llm_timeout_seconds: float = 25.0
    log_level: str = "INFO"


@lru_cache
def get_settings() -> Settings:
    return Settings()

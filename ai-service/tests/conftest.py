import os
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

# Las pruebas nunca deben tomar valores del .env real del repositorio ni llamar a un LLM de verdad.
os.environ["AI_INTERNAL_KEY"] = "test-key"
os.environ["LLM_PROVIDER"] = "local"
os.environ["LLM_API_KEY"] = ""
os.environ["LLM_MODEL"] = "modelo-de-prueba"

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.llm import LlmError  # noqa: E402
from app.main import app, get_service  # noqa: E402
from app.service import CopilotService  # noqa: E402

TEST_KEY = "test-key"

CLASS_DIAGRAM = {
    "schemaVersion": 1,
    "type": "CLASS",
    "classes": [
        {
            "id": "c1",
            "name": "Cliente",
            "x": 10,
            "y": 10,
            "attributes": [{"id": "a1", "name": "nombre", "type": "String", "visibility": "PRIVATE"}],
            "methods": [{"id": "m1", "name": "crearPedido", "returnType": "void", "parameters": []}],
        },
        {
            "id": "c2",
            "name": "Pedido",
            "x": 300,
            "y": 10,
            "attributes": [{"id": "a2", "name": "total", "type": "BigDecimal", "visibility": "PRIVATE"}],
            "methods": [],
        },
    ],
    "relationships": [],
}


class FakeLlmProvider:
    """Proveedor de prueba: devuelve respuestas predefinidas y registra lo que recibió. Nunca sale a la red."""

    def __init__(self, *responses: str, error: LlmError | None = None) -> None:
        self.responses = list(responses)
        self.error = error
        self.calls: list[tuple[str, list[dict]]] = []

    def complete(self, system: str, messages: list[dict]) -> str:
        self.calls.append((system, messages))
        if self.error is not None:
            raise self.error
        if not self.responses:
            raise AssertionError("FakeLlmProvider se llamó más veces de las previstas")
        return self.responses.pop(0)

    @property
    def call_count(self) -> int:
        return len(self.calls)

    @property
    def last_user_message(self) -> str:
        return self.calls[-1][1][-1]["content"]


@pytest.fixture
def make_client():
    """Devuelve un TestClient cuyo servicio usa el FakeLlmProvider indicado."""

    def _make(provider: FakeLlmProvider) -> TestClient:
        app.dependency_overrides[get_service] = lambda: CopilotService(provider)
        return TestClient(app)

    yield _make
    app.dependency_overrides.clear()


@pytest.fixture
def auth() -> dict:
    return {"X-Internal-Key": TEST_KEY}

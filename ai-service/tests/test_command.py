"""POST /v1/command: traduce una orden hablada a la llamada HTTP contra el backend generado."""

import json

from app.llm import LlmTimeout
from conftest import FakeLlmProvider

ENTIDADES = [
    {
        "name": "Cliente",
        "path": "/api/clientes",
        "fields": [{"name": "nombre", "type": "String"}, {"name": "email", "type": "String"}],
    },
    {"name": "Pedido", "path": "/api/pedidos", "fields": [{"name": "total", "type": "BigDecimal"}]},
]

VALIDA = json.dumps(
    {
        "explanation": "Registré el cliente Juan Pérez.",
        "method": "POST",
        "path": "/api/clientes",
        "body": {"nombre": "Juan Pérez", "email": "juan@ejemplo.com"},
    }
)


def body(instruction: str = "registra el cliente Juan Pérez con email juan@ejemplo.com") -> dict:
    return {"instruction": instruction, "entities": ENTIDADES}


def test_traduce_la_orden_a_un_post(make_client, auth):
    provider = FakeLlmProvider(VALIDA)
    r = make_client(provider).post("/v1/command", json=body(), headers=auth)

    assert r.status_code == 200, r.text
    data = r.json()
    assert data["method"] == "POST"
    assert data["path"] == "/api/clientes"
    assert data["body"]["nombre"] == "Juan Pérez"


def test_las_entidades_y_la_orden_viajan_como_datos_delimitados(make_client, auth):
    provider = FakeLlmProvider(VALIDA)
    make_client(provider).post("/v1/command", json=body(), headers=auth)

    mensaje = provider.last_user_message
    assert "<entities_json>" in mensaje and "</entities_json>" in mensaje
    assert "<user_instruction>" in mensaje and "</user_instruction>" in mensaje
    assert "/api/clientes" in mensaje


def test_una_ruta_absoluta_se_rechaza(make_client, auth):
    fuga = json.dumps(
        {"explanation": "x", "method": "POST", "path": "http://otro-host/api/clientes", "body": {}}
    )
    # Dos respuestas: el servicio reintenta una vez antes de rendirse.
    provider = FakeLlmProvider(fuga, fuga)
    r = make_client(provider).post("/v1/command", json=body(), headers=auth)

    assert r.status_code == 502
    assert r.json()["code"] == "AI_INVALID_RESPONSE"


def test_un_metodo_inventado_se_rechaza(make_client, auth):
    raro = json.dumps({"explanation": "x", "method": "PATCH", "path": "/api/clientes", "body": {}})
    provider = FakeLlmProvider(raro, raro)
    r = make_client(provider).post("/v1/command", json=body(), headers=auth)

    assert r.status_code == 502


def test_un_timeout_del_proveedor_se_propaga(make_client, auth):
    provider = FakeLlmProvider(error=LlmTimeout("tardó demasiado"))
    r = make_client(provider).post("/v1/command", json=body(), headers=auth)

    assert r.status_code == 504
    assert r.json()["code"] == "AI_TIMEOUT"


def test_sin_clave_interna_no_responde(make_client):
    r = make_client(FakeLlmProvider(VALIDA)).post("/v1/command", json=body())

    assert r.status_code == 401


def test_sin_entidades_no_se_acepta(make_client, auth):
    r = make_client(FakeLlmProvider(VALIDA)).post(
        "/v1/command", json={"instruction": "registra algo", "entities": []}, headers=auth
    )

    assert r.status_code == 422

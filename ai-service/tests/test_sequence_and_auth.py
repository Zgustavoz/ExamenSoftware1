"""POST /v1/sequence (CU-20), autenticación interna y /health."""

import json

from app.llm import LlmUnavailable
from conftest import CLASS_DIAGRAM, FakeLlmProvider

VALID = json.dumps(
    {
        "lifelines": [{"name": "Cliente", "className": "Cliente"}, {"name": "Pedido", "className": "Pedido"}],
        "messages": [
            {"order": 1, "from": "Cliente", "to": "Pedido", "name": "crearPedido", "kind": "SYNC"},
            {"order": 2, "from": "Pedido", "to": "Cliente", "name": "pedidoCreado", "kind": "RETURN"},
        ],
    }
)


def body() -> dict:
    return {"contentJson": CLASS_DIAGRAM}


# ---------------------------------------------------------------- /v1/sequence


def test_devuelve_lineas_de_vida_y_mensajes(make_client, auth):
    fake = FakeLlmProvider(VALID)
    r = make_client(fake).post("/v1/sequence", json=body(), headers=auth)

    assert r.status_code == 200
    data = r.json()
    assert [l["name"] for l in data["lifelines"]] == ["Cliente", "Pedido"]
    assert data["messages"][0]["from"] == "Cliente"
    assert data["messages"][0]["name"] == "crearPedido"
    assert data["messages"][1]["kind"] == "RETURN"


def test_el_diagrama_de_clases_viaja_como_dato(make_client, auth):
    fake = FakeLlmProvider(VALID)
    make_client(fake).post("/v1/sequence", json=body(), headers=auth)

    message = fake.last_user_message
    assert message.startswith("<diagram_json>")
    assert "crearPedido" in message  # los métodos existentes son el contexto para nombrar mensajes


def test_mensaje_que_referencia_una_linea_inexistente_es_502(make_client, auth):
    invalida = json.dumps(
        {"lifelines": [{"name": "Cliente"}], "messages": [{"order": 1, "from": "Cliente", "to": "Fantasma", "name": "x"}]}
    )
    fake = FakeLlmProvider(invalida, invalida)
    r = make_client(fake).post("/v1/sequence", json=body(), headers=auth)

    assert r.status_code == 502
    assert r.json()["code"] == "AI_INVALID_RESPONSE"


def test_sin_mensajes_o_sin_lineas_es_502(make_client, auth):
    vacia = json.dumps({"lifelines": [], "messages": []})
    fake = FakeLlmProvider(vacia, vacia)
    assert make_client(fake).post("/v1/sequence", json=body(), headers=auth).status_code == 502


def test_tipo_de_mensaje_invalido_es_502(make_client, auth):
    invalida = json.dumps(
        {"lifelines": [{"name": "A"}], "messages": [{"order": 1, "from": "A", "to": "A", "name": "x", "kind": "GRITO"}]}
    )
    fake = FakeLlmProvider(invalida, invalida)
    assert make_client(fake).post("/v1/sequence", json=body(), headers=auth).status_code == 502


def test_fallo_del_proveedor_es_503(make_client, auth):
    fake = FakeLlmProvider(error=LlmUnavailable("caído"))
    r = make_client(fake).post("/v1/sequence", json=body(), headers=auth)

    assert r.status_code == 503
    assert r.json()["code"] == "AI_UNAVAILABLE"


# ---------------------------------------------------------------- autenticación interna


def test_sin_clave_interna_es_401(make_client):
    fake = FakeLlmProvider(VALID)
    client = make_client(fake)

    assert client.post("/v1/sequence", json=body()).status_code == 401
    assert client.post("/v1/interpret", json={"instruction": "x", "contentJson": {}}).status_code == 401
    assert fake.call_count == 0


def test_clave_interna_incorrecta_es_401(make_client):
    fake = FakeLlmProvider(VALID)
    r = make_client(fake).post("/v1/sequence", json=body(), headers={"X-Internal-Key": "otra-clave"})

    assert r.status_code == 401
    assert r.json()["code"] == "UNAUTHORIZED"
    assert fake.call_count == 0


def test_health_no_requiere_clave(make_client):
    r = make_client(FakeLlmProvider()).get("/health")

    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_no_se_publica_documentacion_interactiva(make_client):
    client = make_client(FakeLlmProvider())

    assert client.get("/docs").status_code == 404
    assert client.get("/openapi.json").status_code == 404

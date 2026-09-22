"""Unidades: extracción de JSON, selección de proveedor y traducción de fallos HTTP del LLM."""

import httpx
import pytest

from app.config import Settings
from app.llm import LlmTimeout, LlmUnavailable, build_provider
from app.llm.openai_compatible import OpenAICompatibleProvider
from app.service import extract_json


# ---------------------------------------------------------------- extract_json


@pytest.mark.parametrize(
    "raw",
    [
        '{"a": 1}',
        '```json\n{"a": 1}\n```',
        '```\n{"a": 1}\n```',
        'Claro, aquí tienes:\n{"a": 1}\nEspero que sirva.',
        '   \n {"a": 1}  ',
    ],
)
def test_extrae_el_objeto_json(raw):
    assert extract_json(raw) == {"a": 1}


@pytest.mark.parametrize("raw", ["", "sin json", "[1, 2, 3]", "{roto", "null"])
def test_rechaza_lo_que_no_es_un_objeto_json(raw):
    with pytest.raises(ValueError):
        extract_json(raw)


# ---------------------------------------------------------------- proveedor


def test_openai_usa_json_mode_y_su_url_por_defecto():
    p = build_provider(Settings(llm_provider="openai", llm_model="m", llm_api_key="k"))

    assert isinstance(p, OpenAICompatibleProvider)
    assert p.base_url == "https://api.openai.com/v1"
    assert p.json_mode is True


def test_local_no_usa_json_mode_y_admite_url_propia():
    p = build_provider(Settings(llm_provider="local", llm_model="m", llm_base_url="http://mi-llm:8080/v1"))

    assert p.base_url == "http://mi-llm:8080/v1"
    assert p.json_mode is False


def test_no_hay_ningun_modelo_fijado_en_el_codigo(monkeypatch):
    # Sin LLM_MODEL configurado no debe aparecer ningún nombre de modelo por defecto.
    monkeypatch.delenv("LLM_MODEL", raising=False)
    assert build_provider(Settings(_env_file=None)).model == ""


def _provider(handler, **kwargs) -> OpenAICompatibleProvider:
    client = httpx.Client(transport=httpx.MockTransport(handler))
    return OpenAICompatibleProvider(base_url="http://llm/v1", model="m", client=client, **kwargs)


def test_devuelve_el_contenido_del_mensaje():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/chat/completions"
        return httpx.Response(200, json={"choices": [{"message": {"content": "hola"}}]})

    assert _provider(handler).complete("sistema", [{"role": "user", "content": "hola"}]) == "hola"


def test_envia_la_clave_y_el_prompt_de_sistema():
    capturado = {}

    def handler(request: httpx.Request) -> httpx.Response:
        import json as _json

        capturado["auth"] = request.headers.get("Authorization")
        capturado["body"] = _json.loads(request.content)
        return httpx.Response(200, json={"choices": [{"message": {"content": "{}"}}]})

    _provider(handler, api_key="secreta").complete("SOY EL SISTEMA", [{"role": "user", "content": "hola"}])

    assert capturado["auth"] == "Bearer secreta"
    assert capturado["body"]["messages"][0] == {"role": "system", "content": "SOY EL SISTEMA"}


@pytest.mark.parametrize("status", [408, 504])
def test_los_timeouts_http_se_traducen(status):
    with pytest.raises(LlmTimeout):
        _provider(lambda r: httpx.Response(status)).complete("s", [])


def test_el_timeout_de_red_se_traduce():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("tardó demasiado", request=request)

    with pytest.raises(LlmTimeout):
        _provider(handler).complete("s", [])


@pytest.mark.parametrize("status", [401, 403, 429, 500, 503])
def test_los_demas_errores_son_servicio_no_disponible(status):
    with pytest.raises(LlmUnavailable):
        _provider(lambda r: httpx.Response(status)).complete("s", [])


def test_error_de_conexion_es_servicio_no_disponible():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("sin ruta al host", request=request)

    with pytest.raises(LlmUnavailable):
        _provider(handler).complete("s", [])


def test_respuesta_sin_la_forma_esperada_es_servicio_no_disponible():
    with pytest.raises(LlmUnavailable):
        _provider(lambda r: httpx.Response(200, json={"algo": "raro"})).complete("s", [])


def test_sin_modelo_configurado_no_se_llama_al_proveedor():
    llamado = False

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal llamado
        llamado = True
        return httpx.Response(200, json={})

    client = httpx.Client(transport=httpx.MockTransport(handler))
    provider = OpenAICompatibleProvider(base_url="http://llm/v1", model="", client=client)

    with pytest.raises(LlmUnavailable):
        provider.complete("s", [])
    assert llamado is False


# ---------------------------------------------------------------- proveedores que rechazan response_format


def test_si_el_proveedor_rechaza_response_format_reintenta_sin_el():
    cuerpos = []

    def handler(request: httpx.Request) -> httpx.Response:
        import json as _json

        body = _json.loads(request.content)
        cuerpos.append(body)
        if "response_format" in body:
            return httpx.Response(400, json={"error": "response_format no soportado"})
        return httpx.Response(200, json={"choices": [{"message": {"content": "{}"}}]})

    provider = _provider(handler, json_mode=True)

    assert provider.complete("s", [{"role": "user", "content": "hola"}]) == "{}"
    assert len(cuerpos) == 2
    assert "response_format" in cuerpos[0] and "response_format" not in cuerpos[1]


def test_recuerda_que_no_hay_modo_json_y_no_vuelve_a_intentarlo():
    llamadas = []

    def handler(request: httpx.Request) -> httpx.Response:
        import json as _json

        body = _json.loads(request.content)
        llamadas.append("response_format" in body)
        if "response_format" in body:
            return httpx.Response(400)
        return httpx.Response(200, json={"choices": [{"message": {"content": "{}"}}]})

    provider = _provider(handler, json_mode=True)
    provider.complete("s", [])
    provider.complete("s", [])

    assert llamadas == [True, False, False]  # la segunda llamada ya no envía response_format


def test_un_400_sin_modo_json_es_servicio_no_disponible():
    with pytest.raises(LlmUnavailable):
        _provider(lambda r: httpx.Response(400), json_mode=False).complete("s", [])


def test_un_404_de_modelo_inexistente_es_servicio_no_disponible():
    with pytest.raises(LlmUnavailable):
        _provider(lambda r: httpx.Response(404, json={"error": "modelo no encontrado"})).complete("s", [])

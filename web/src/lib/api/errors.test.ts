import { ApiError, apiErrorFromBody, apiErrorFromGraphQl, errorMessage } from './errors'

describe('apiErrorFromBody', () => {
  it('lee el formato de error del backend (sección 7.1)', () => {
    const error = apiErrorFromBody(
      { code: 'DUPLICATE_RELATIONSHIP', message: 'La conexión entre esas clases ya existe.', details: { a: 1 } },
      422,
    )
    expect(error).toBeInstanceOf(ApiError)
    expect(error.code).toBe('DUPLICATE_RELATIONSHIP')
    expect(error.message).toBe('La conexión entre esas clases ya existe.')
    expect(error.status).toBe(422)
    expect(error.details).toEqual({ a: 1 })
  })

  it('cae en UNKNOWN con un mensaje en español si el cuerpo no tiene el formato esperado', () => {
    const error = apiErrorFromBody('<html>502 Bad Gateway</html>', 502)
    expect(error.code).toBe('UNKNOWN')
    expect(error.message).toMatch(/inesperado/)
  })
})

describe('apiErrorFromGraphQl', () => {
  it('toma el código de extensions y conserva details (VERSION_CONFLICT)', () => {
    const details = { currentVersion: 7, contentJson: { classes: [] } }
    const error = apiErrorFromGraphQl(
      { message: 'El diagrama cambió.', extensions: { code: 'VERSION_CONFLICT', details } },
      200,
    )
    expect(error.is('VERSION_CONFLICT')).toBe(true)
    expect(error.details).toEqual(details)
  })

  it('devuelve UNKNOWN si el error no trae código', () => {
    expect(apiErrorFromGraphQl({ message: 'boom' }, 200).code).toBe('UNKNOWN')
  })
})

describe('errorMessage', () => {
  it('nunca filtra el mensaje de un error que no es del backend', () => {
    expect(errorMessage(new Error('stack interno'))).not.toMatch(/stack interno/)
  })
})

import { configure } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'

// Las rutas se cargan de forma diferida: la primera vez que una prueba abre una pantalla, Vite tiene que
// transformar el modulo, y el margen por defecto de un segundo se queda corto.
configure({ asyncUtilTimeout: 5000 })

// jsdom no implementa estas API del navegador, de las que dependen los componentes de Radix (diálogos,
// checkbox, select…). Sin ellas el componente lanza al montarse, aunque en el navegador funcione.
class ResizeObserverStub implements ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

globalThis.ResizeObserver ??= ResizeObserverStub

Element.prototype.hasPointerCapture ??= () => false
Element.prototype.setPointerCapture ??= () => {}
Element.prototype.releasePointerCapture ??= () => {}
Element.prototype.scrollIntoView ??= () => {}

import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Runs `load` whenever `deps` change and tracks loading, data and error.
 * Answers that arrive after a newer request started are ignored, so a slow
 * response can never overwrite a newer one.
 */
export function useAsync(load, deps) {
  const [state, setState] = useState({ loading: true, data: null, error: null })
  const latest = useRef(0)

  const run = useCallback(() => {
    const id = ++latest.current
    setState((current) => ({ ...current, loading: true, error: null }))
    return load()
      .then((data) => {
        if (id === latest.current) setState({ loading: false, data, error: null })
      })
      .catch((error) => {
        if (id === latest.current) setState((current) => ({ loading: false, data: current.data, error }))
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  useEffect(() => {
    run()
  }, [run])

  return { ...state, reload: run }
}

import { Component } from 'react'

/**
 * The last safety net: if a page throws while drawing, show a calm message
 * with a way back instead of a blank screen. React itself reports the error
 * to the browser console, so nothing is logged here.
 */
export default class ErrorBoundary extends Component {
  state = { failed: false }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  render() {
    if (!this.state.failed) return this.props.children

    return (
      <main className="message-page">
        <div role="alert">
          <h1>Something went wrong</h1>
          <p>
            This page could not be shown. Reload to try again. If you were moving money, open your history before trying
            again, to see whether it went through.
          </p>
        </div>
        <div className="page-actions">
          <button type="button" className="button button-primary" onClick={() => window.location.reload()}>
            Reload the page
          </button>
          <a className="button button-ghost" href="/">
            Go to the start page
          </a>
        </div>
      </main>
    )
  }
}

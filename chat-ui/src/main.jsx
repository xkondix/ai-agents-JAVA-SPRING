import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import App from './App.jsx'
import ApprovalsPage from './pages/ApprovalsPage.jsx'
import PatternsPage from './pages/PatternsPage.jsx'
import MultimodalPage from './pages/MultimodalPage.jsx'
import MediaViewerPage from './pages/MediaViewerPage.jsx'
import './index.css'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<App />} />
        <Route path="/approvals" element={<ApprovalsPage />} />
        <Route path="/patterns" element={<PatternsPage />} />
        {/* Its own page rather than an agent in the sidebar: the composer
            needs a file picker and a microphone, which the shared ChatWindow
            has no reason to carry. */}
        <Route path="/multimodal" element={<MultimodalPage />} />
        {/* A route, not a modal — an artifact can then be linked, reloaded
            and shown full-screen on a projector. */}
        <Route path="/multimodal/view" element={<MediaViewerPage />} />
      </Routes>
    </BrowserRouter>
  </React.StrictMode>
)

import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import App from './App.jsx'
import ApprovalsPage from './pages/ApprovalsPage.jsx'
import PatternsPage from './pages/PatternsPage.jsx'
import './index.css'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<App />} />
        <Route path="/approvals" element={<ApprovalsPage />} />
        <Route path="/patterns" element={<PatternsPage />} />
      </Routes>
    </BrowserRouter>
  </React.StrictMode>
)

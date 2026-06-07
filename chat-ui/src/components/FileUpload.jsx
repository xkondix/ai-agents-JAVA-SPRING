import { Paperclip, X, Image, FileText } from 'lucide-react'
import { useRef } from 'react'

export default function FileUpload({ file, onFile, onClear, disabled }) {
  const ref = useRef()

  const handleChange = e => {
    const f = e.target.files?.[0]
    if (f) onFile(f)
    e.target.value = ''
  }

  const isImage = file?.type?.startsWith('image/')

  return (
    <div>
      <input
        ref={ref}
        type="file"
        accept="image/*,.pdf,.txt,.md,.java,.py,.json,.yaml,.yml"
        className="hidden"
        onChange={handleChange}
      />

      {file ? (
        <div className="flex items-center gap-2 px-3 py-1.5 bg-slate-700 rounded-lg text-sm">
          {isImage
            ? <Image size={14} className="text-blue-400" />
            : <FileText size={14} className="text-slate-400" />}
          <span className="text-slate-300 truncate max-w-[160px]">{file.name}</span>
          <button
            onClick={onClear}
            className="text-slate-500 hover:text-red-400 transition-colors"
          >
            <X size={13} />
          </button>
        </div>
      ) : (
        <button
          disabled={disabled}
          onClick={() => ref.current?.click()}
          className="p-2 rounded-lg text-slate-500 hover:text-slate-300 hover:bg-slate-700 transition-colors disabled:opacity-40"
          title="Add file or image"
        >
          <Paperclip size={18} />
        </button>
      )}
    </div>
  )
}

"use client";

import { useEffect, useState } from "react";
import { Loader2, Plus, Pencil, Trash2 } from "lucide-react";

import {
  createAnnouncement,
  deleteAnnouncement,
  getAllAnnouncements,
  updateAnnouncement,
  type Announcement,
} from "@/lib/api";

const TYPE_STYLES: Record<Announcement["type"], string> = {
  INFO: "bg-sage-navy/10 text-sage-navy",
  SUCCESS: "bg-emerald-100 text-emerald-700",
  WARNING: "bg-amber-100 text-amber-700",
};

export function AdminAnnouncementsTab() {
  const [rows, setRows] = useState<Announcement[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [editing, setEditing] = useState<Announcement | null>(null);
  const [showForm, setShowForm] = useState(false);

  const refresh = async () => {
    setLoading(true);
    try {
      const data = await getAllAnnouncements();
      setRows(data ?? []);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void refresh(); }, []);

  const remove = async (id: number) => {
    if (!window.confirm("Delete this announcement?")) return;
    try {
      await deleteAnnouncement(id);
      setRows((prev) => prev.filter((a) => a.id !== id));
    } catch (e) {
      alert(e instanceof Error ? e.message : "Couldn't delete");
    }
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-5 gap-4 flex-wrap">
        <h1 className="text-2xl font-bold text-zinc-900">Announcements ({rows.length})</h1>
        <button
          onClick={() => { setEditing(null); setShowForm(true); }}
          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep transition cursor-pointer"
        >
          <Plus size={12} /> New announcement
        </button>
      </div>

      {error && (
        <div className="mb-4 px-4 py-2.5 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">{error}</div>
      )}

      {loading ? (
        <div className="text-center py-10"><Loader2 size={20} className="animate-spin text-sage-navy inline" /></div>
      ) : (
        <div className="space-y-3">
          {rows.length === 0 ? (
            <p className="text-zinc-500 text-center py-12">No announcements yet.</p>
          ) : (
            rows.map((a) => (
              <div key={a.id} className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl p-4">
                <div className="flex items-start justify-between gap-4 flex-wrap">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1 flex-wrap">
                      <h3 className="font-semibold text-zinc-900">{a.title}</h3>
                      <span className={"text-[10px] px-2 py-0.5 rounded-full font-semibold " + TYPE_STYLES[a.type]}>{a.type}</span>
                      {!a.isActive && <span className="text-[10px] px-2 py-0.5 rounded-full bg-zinc-100 text-zinc-500 font-semibold">INACTIVE</span>}
                    </div>
                    <p className="text-sm text-zinc-600">{a.message}</p>
                    <p className="text-[10px] text-zinc-400 mt-1.5">
                      Created {new Date(a.createdAt).toLocaleDateString("en-IN")}
                      {a.expiresAt && <> · expires {new Date(a.expiresAt).toLocaleDateString("en-IN")}</>}
                      {a.createdByName && <> · by {a.createdByName}</>}
                    </p>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    <button onClick={() => { setEditing(a); setShowForm(true); }} className="p-2 rounded-lg hover:bg-zinc-100 text-zinc-500 hover:text-sage-navy transition" title="Edit">
                      <Pencil size={14} />
                    </button>
                    <button onClick={() => remove(a.id)} className="p-2 rounded-lg hover:bg-red-50 text-zinc-500 hover:text-red-700 transition" title="Delete">
                      <Trash2 size={14} />
                    </button>
                  </div>
                </div>
              </div>
            ))
          )}
        </div>
      )}

      {showForm && (
        <AnnouncementForm
          initial={editing}
          onClose={() => setShowForm(false)}
          onSaved={async () => { setShowForm(false); await refresh(); }}
        />
      )}
    </div>
  );
}

function AnnouncementForm({ initial, onClose, onSaved }: { initial: Announcement | null; onClose: () => void; onSaved: () => Promise<void>; }) {
  const [title, setTitle] = useState(initial?.title ?? "");
  const [message, setMessage] = useState(initial?.message ?? "");
  const [type, setType] = useState<Announcement["type"]>(initial?.type ?? "INFO");
  const [isActive, setIsActive] = useState(initial?.isActive ?? true);
  const [expiresAt, setExpiresAt] = useState(initial?.expiresAt?.slice(0, 10) ?? "");
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState("");

  const submit = async () => {
    if (!title.trim() || !message.trim()) return;
    setSaving(true);
    setErr("");
    try {
      const payload = { title: title.trim(), message: message.trim(), type, isActive, expiresAt: expiresAt || null };
      if (initial) await updateAnnouncement(initial.id, payload);
      else await createAnnouncement(payload);
      await onSaved();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "Couldn't save");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-40 bg-black/40 flex items-center justify-center p-4" onClick={onClose}>
      <div className="bg-white rounded-2xl shadow-xl w-full max-w-md p-5" onClick={(e) => e.stopPropagation()}>
        <h2 className="text-lg font-bold text-zinc-900 mb-3">{initial ? "Edit" : "New"} announcement</h2>
        <div className="space-y-3">
          <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Title" className="w-full px-3 py-2 text-sm rounded-md border border-zinc-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy" />
          <textarea value={message} onChange={(e) => setMessage(e.target.value)} placeholder="Message" rows={3} className="w-full px-3 py-2 text-sm rounded-md border border-zinc-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy" />
          <div className="flex items-center gap-3 flex-wrap">
            <select value={type} onChange={(e) => setType(e.target.value as Announcement["type"])} className="px-3 py-2 text-sm rounded-md border border-zinc-200">
              <option value="INFO">INFO</option>
              <option value="SUCCESS">SUCCESS</option>
              <option value="WARNING">WARNING</option>
            </select>
            <input type="date" value={expiresAt} onChange={(e) => setExpiresAt(e.target.value)} className="px-3 py-2 text-sm rounded-md border border-zinc-200" />
            <label className="inline-flex items-center gap-2 text-sm text-zinc-700">
              <input type="checkbox" checked={isActive} onChange={(e) => setIsActive(e.target.checked)} className="rounded" />
              Active
            </label>
          </div>
          {err && <p className="text-sm text-red-700">{err}</p>}
        </div>
        <div className="flex justify-end gap-2 mt-4">
          <button onClick={onClose} className="px-3 py-1.5 text-xs font-semibold text-zinc-600 hover:text-zinc-900 cursor-pointer">Cancel</button>
          <button onClick={submit} disabled={saving || !title.trim() || !message.trim()} className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 cursor-pointer">
            {saving ? <Loader2 size={12} className="animate-spin" /> : null}
            {initial ? "Save" : "Create"}
          </button>
        </div>
      </div>
    </div>
  );
}

"use client";

import { useEffect, useState } from "react";
import { Loader2, Plus, Pencil, Trash2 } from "lucide-react";

import {
  createCoupon,
  deleteCoupon,
  getAllCoupons,
  updateCoupon,
  type Coupon,
} from "@/lib/api";

export function AdminCouponsTab() {
  const [rows, setRows] = useState<Coupon[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [editing, setEditing] = useState<Coupon | null>(null);
  const [showForm, setShowForm] = useState(false);

  const refresh = async () => {
    setLoading(true);
    try {
      const data = await getAllCoupons();
      setRows(data ?? []);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void refresh(); }, []);

  const remove = async (id: number) => {
    if (!window.confirm("Delete this coupon?")) return;
    try {
      await deleteCoupon(id);
      setRows((prev) => prev.filter((c) => c.id !== id));
    } catch (e) {
      alert(e instanceof Error ? e.message : "Couldn't delete");
    }
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-5 gap-4 flex-wrap">
        <h1 className="text-2xl font-bold text-zinc-900">Coupons ({rows.length})</h1>
        <button
          onClick={() => { setEditing(null); setShowForm(true); }}
          className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep transition cursor-pointer"
        >
          <Plus size={12} /> New coupon
        </button>
      </div>

      {error && (
        <div className="mb-4 px-4 py-2.5 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">{error}</div>
      )}

      {loading ? (
        <div className="text-center py-10"><Loader2 size={20} className="animate-spin text-sage-navy inline" /></div>
      ) : (
        <div className="bg-white/60 backdrop-blur-xl border border-zinc-200 rounded-2xl overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-zinc-200 text-zinc-600">
                  <th className="text-left px-4 py-3 font-medium">Code</th>
                  <th className="text-left px-4 py-3 font-medium">Discount</th>
                  <th className="text-left px-4 py-3 font-medium">Min order</th>
                  <th className="text-left px-4 py-3 font-medium">Uses</th>
                  <th className="text-left px-4 py-3 font-medium">Expires</th>
                  <th className="text-left px-4 py-3 font-medium">Status</th>
                  <th className="text-right px-4 py-3 font-medium">Actions</th>
                </tr>
              </thead>
              <tbody>
                {rows.length === 0 ? (
                  <tr>
                    <td colSpan={7} className="px-4 py-6 text-center text-zinc-400 italic">No coupons yet.</td>
                  </tr>
                ) : (
                  rows.map((c) => (
                    <tr key={c.id} className="border-b border-zinc-100">
                      <td className="px-4 py-3 font-mono text-xs font-bold text-zinc-900">{c.code}</td>
                      <td className="px-4 py-3 text-zinc-700">
                        {c.discountType === "PERCENT" ? `${c.discountValue}%` : `₹${c.discountValue.toLocaleString("en-IN")}`}
                      </td>
                      <td className="px-4 py-3 text-zinc-700 text-xs">{c.minOrderAmount != null ? `₹${c.minOrderAmount.toLocaleString("en-IN")}` : "—"}</td>
                      <td className="px-4 py-3 text-zinc-700 tabular-nums text-xs">
                        {c.usesCount}{c.maxUses != null ? ` / ${c.maxUses}` : ""}
                      </td>
                      <td className="px-4 py-3 text-zinc-500 text-xs">{c.expiresAt ? new Date(c.expiresAt).toLocaleDateString("en-IN") : "—"}</td>
                      <td className="px-4 py-3">
                        <span className={"text-xs px-2 py-0.5 rounded-full font-semibold " + (c.isActive ? "bg-emerald-100 text-emerald-700" : "bg-zinc-100 text-zinc-500")}>
                          {c.isActive ? "ACTIVE" : "INACTIVE"}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-right">
                        <div className="inline-flex gap-1">
                          <button onClick={() => { setEditing(c); setShowForm(true); }} className="p-1.5 rounded hover:bg-zinc-100 text-zinc-500 hover:text-sage-navy transition" title="Edit">
                            <Pencil size={13} />
                          </button>
                          <button onClick={() => remove(c.id)} className="p-1.5 rounded hover:bg-red-50 text-zinc-500 hover:text-red-700 transition" title="Delete">
                            <Trash2 size={13} />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {showForm && (
        <CouponForm
          initial={editing}
          onClose={() => setShowForm(false)}
          onSaved={async () => { setShowForm(false); await refresh(); }}
        />
      )}
    </div>
  );
}

function CouponForm({ initial, onClose, onSaved }: { initial: Coupon | null; onClose: () => void; onSaved: () => Promise<void>; }) {
  const [code, setCode] = useState(initial?.code ?? "");
  const [discountType, setDiscountType] = useState<"PERCENT" | "FLAT">(initial?.discountType ?? "PERCENT");
  const [discountValue, setDiscountValue] = useState(String(initial?.discountValue ?? 10));
  const [minOrderAmount, setMinOrderAmount] = useState(initial?.minOrderAmount != null ? String(initial.minOrderAmount) : "");
  const [maxUses, setMaxUses] = useState(initial?.maxUses != null ? String(initial.maxUses) : "");
  const [expiresAt, setExpiresAt] = useState(initial?.expiresAt?.slice(0, 10) ?? "");
  const [isActive, setIsActive] = useState(initial?.isActive ?? true);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState("");

  const submit = async () => {
    if (!code.trim()) return;
    setSaving(true);
    setErr("");
    try {
      if (initial) {
        await updateCoupon(initial.id, {
          discountType,
          discountValue: Number(discountValue),
          minOrderAmount: minOrderAmount ? Number(minOrderAmount) : null,
          maxUses: maxUses ? Number(maxUses) : null,
          expiresAt: expiresAt || null,
          isActive,
        });
      } else {
        await createCoupon({
          code: code.trim().toUpperCase(),
          discountType,
          discountValue: Number(discountValue),
          minOrderAmount: minOrderAmount ? Number(minOrderAmount) : null,
          maxUses: maxUses ? Number(maxUses) : null,
          expiresAt: expiresAt || null,
          isActive,
        });
      }
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
        <h2 className="text-lg font-bold text-zinc-900 mb-3">{initial ? "Edit" : "New"} coupon</h2>
        <div className="space-y-3">
          <input value={code} onChange={(e) => setCode(e.target.value)} placeholder="Code (e.g. SAGE10)" disabled={!!initial} className="w-full px-3 py-2 text-sm rounded-md border border-zinc-200 font-mono uppercase focus:outline-none focus:border-sage-navy disabled:bg-zinc-50" />
          <div className="grid grid-cols-2 gap-2">
            <select value={discountType} onChange={(e) => setDiscountType(e.target.value as "PERCENT" | "FLAT")} className="px-3 py-2 text-sm rounded-md border border-zinc-200">
              <option value="PERCENT">Percent</option>
              <option value="FLAT">Flat (INR)</option>
            </select>
            <input type="number" value={discountValue} onChange={(e) => setDiscountValue(e.target.value)} placeholder="Value" className="px-3 py-2 text-sm rounded-md border border-zinc-200 focus:outline-none focus:border-sage-navy" />
          </div>
          <div className="grid grid-cols-2 gap-2">
            <input type="number" value={minOrderAmount} onChange={(e) => setMinOrderAmount(e.target.value)} placeholder="Min order (₹)" className="px-3 py-2 text-sm rounded-md border border-zinc-200" />
            <input type="number" value={maxUses} onChange={(e) => setMaxUses(e.target.value)} placeholder="Max uses" className="px-3 py-2 text-sm rounded-md border border-zinc-200" />
          </div>
          <input type="date" value={expiresAt} onChange={(e) => setExpiresAt(e.target.value)} className="w-full px-3 py-2 text-sm rounded-md border border-zinc-200" />
          <label className="inline-flex items-center gap-2 text-sm text-zinc-700">
            <input type="checkbox" checked={isActive} onChange={(e) => setIsActive(e.target.checked)} className="rounded" />
            Active
          </label>
          {err && <p className="text-sm text-red-700">{err}</p>}
        </div>
        <div className="flex justify-end gap-2 mt-4">
          <button onClick={onClose} className="px-3 py-1.5 text-xs font-semibold text-zinc-600 hover:text-zinc-900 cursor-pointer">Cancel</button>
          <button onClick={submit} disabled={saving || !code.trim()} className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 cursor-pointer">
            {saving ? <Loader2 size={12} className="animate-spin" /> : null}
            {initial ? "Save" : "Create"}
          </button>
        </div>
      </div>
    </div>
  );
}

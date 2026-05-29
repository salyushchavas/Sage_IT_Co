"use client";

import { useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { Loader2, X, Plus, Trash2 } from "lucide-react";
import { sendSalesQuote, type SalesQuoteItem } from "@/lib/api";
import { useToast } from "@/components/ui/Toast";

interface Props {
  isOpen: boolean;
  onClose: () => void;
  inquiryId: number;
  courseTitle: string;
  defaultItem?: { item: string; price: number };
  onQuoteSent: () => void;
}

interface DraftItem {
  item: string;
  price: string;
}

export function SendQuoteModal({
  isOpen, onClose, inquiryId, courseTitle, defaultItem, onQuoteSent,
}: Props) {
  const { toast } = useToast();
  const [items, setItems] = useState<DraftItem[]>(() =>
    defaultItem
      ? [{ item: defaultItem.item, price: String(defaultItem.price) }]
      : [{ item: courseTitle, price: "" }]
  );
  const [message, setMessage] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  const addItem = () => setItems((prev) => [...prev, { item: "", price: "" }]);
  const removeItem = (idx: number) => setItems((prev) => prev.filter((_, i) => i !== idx));
  const updateItem = (idx: number, patch: Partial<DraftItem>) =>
    setItems((prev) => prev.map((it, i) => (i === idx ? { ...it, ...patch } : it)));

  const total = items.reduce((sum, it) => {
    const n = Number(it.price);
    return sum + (Number.isFinite(n) ? n : 0);
  }, 0);

  const submit = async () => {
    const cleanItems: SalesQuoteItem[] = items
      .filter((it) => it.item.trim() !== "" && it.price !== "")
      .map((it) => ({ item: it.item.trim(), price: Number(it.price) }));

    if (cleanItems.length === 0) {
      setError("Add at least one line item.");
      return;
    }
    if (total < 0) {
      setError("Total price can't be negative.");
      return;
    }
    setSubmitting(true);
    setError("");
    try {
      await sendSalesQuote(inquiryId, {
        message: message.trim() || "Here's a custom quote for you.",
        quotedPrice: total,
        quotedItems: cleanItems,
      });
      toast("success", "Quote sent.");
      onQuoteSent();
      onClose();
      setItems(defaultItem
        ? [{ item: defaultItem.item, price: String(defaultItem.price) }]
        : [{ item: courseTitle, price: "" }]);
      setMessage("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't send quote");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AnimatePresence>
      {isOpen && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          className="fixed inset-0 z-50 flex items-center justify-center px-4 bg-black/40 backdrop-blur-sm"
          onClick={onClose}
        >
          <motion.div
            initial={{ scale: 0.95, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            exit={{ scale: 0.95, opacity: 0 }}
            transition={{ duration: 0.18 }}
            onClick={(e) => e.stopPropagation()}
            className="bg-white rounded-2xl shadow-xl w-full max-w-lg p-6"
          >
            <div className="flex items-start justify-between mb-4">
              <div>
                <h2 className="text-lg font-bold text-gray-900">Send Price Quote</h2>
                <p className="text-xs text-gray-500">Custom offer for {courseTitle}</p>
              </div>
              <button
                onClick={onClose}
                className="text-gray-400 hover:text-gray-600 cursor-pointer"
                aria-label="Close"
              >
                <X size={18} />
              </button>
            </div>

            <div className="space-y-3 mb-5">
              <p className="text-xs font-medium text-gray-700">Line items</p>
              {items.map((it, idx) => (
                <div key={idx} className="flex items-center gap-2">
                  <input
                    type="text"
                    value={it.item}
                    onChange={(e) => updateItem(idx, { item: e.target.value })}
                    placeholder="Course / service / bundle discount"
                    className="flex-1 px-3 py-2 rounded-lg border border-gray-300 bg-white text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
                  />
                  <input
                    type="number"
                    step="0.01"
                    value={it.price}
                    onChange={(e) => updateItem(idx, { price: e.target.value })}
                    placeholder="₹"
                    className="w-28 px-3 py-2 rounded-lg border border-gray-300 bg-white text-sm tabular-nums focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
                  />
                  <button
                    onClick={() => removeItem(idx)}
                    disabled={items.length === 1}
                    className="p-2 text-gray-400 hover:text-red-700 disabled:opacity-30 cursor-pointer"
                    aria-label="Remove line"
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              ))}
              <button
                onClick={addItem}
                className="inline-flex items-center gap-1 text-xs font-semibold text-sage-navy hover:text-sage-navy-deep cursor-pointer"
              >
                <Plus size={12} /> Add line item
              </button>
              <p className="text-[10px] text-gray-400">
                Use a negative number for bundle discounts (e.g. -500).
              </p>
            </div>

            <div className="flex items-center justify-between bg-sage-navy/5 rounded-lg px-4 py-3 mb-5">
              <span className="text-sm font-semibold text-gray-700">Total</span>
              <span className="text-xl font-bold text-sage-navy tabular-nums">
                ₹{total.toLocaleString("en-IN")}
              </span>
            </div>

            <div className="mb-4">
              <label className="text-xs font-medium text-gray-700">Message with quote</label>
              <textarea
                value={message}
                onChange={(e) => setMessage(e.target.value)}
                rows={3}
                placeholder="Here's a special offer for you…"
                className="mt-1 w-full px-3 py-2 rounded-lg border border-gray-300 bg-white text-sm focus:outline-none focus:ring-2 focus:ring-sage-navy/30"
              />
            </div>

            {error && <p className="text-xs text-red-700 mb-3">{error}</p>}

            <div className="flex items-center justify-end gap-2">
              <button
                onClick={onClose}
                disabled={submitting}
                className="px-4 py-2 rounded-lg text-sm font-medium text-gray-600 border border-gray-300 hover:bg-gray-50 disabled:opacity-50 transition cursor-pointer"
              >
                Cancel
              </button>
              <button
                onClick={submit}
                disabled={submitting}
                className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-50 transition cursor-pointer"
              >
                {submitting && <Loader2 size={14} className="animate-spin" />}
                Send Quote
              </button>
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

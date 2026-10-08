"use client";

import { useEffect, useId, type ReactNode } from "react";
import { X } from "lucide-react";

/**
 * The ERM detail's dialog frame, shared by Send for approval, Approve & sign
 * and Advance to Phase 2. A copy of WebAgreementDetailView's private
 * ModalShell (same header, body and footer), made to fit phones: below `sm`
 * it is a full-width sheet at the bottom of the screen; it is never taller
 * than 96% of the screen and scrolls inside itself, with the footer kept in
 * view and its buttons wrapping.
 *
 * Escape, the backdrop and the close button only work while `closeable`
 * (off while a request is in flight).
 */
export function ModalShell({
  title,
  subtitle,
  onClose,
  closeable,
  children,
  footer,
}: {
  title: string;
  subtitle?: string;
  onClose: () => void;
  closeable: boolean;
  children: ReactNode;
  footer: ReactNode;
}) {
  const titleId = useId();

  useEffect(() => {
    if (!closeable) return;
    const onEsc = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onEsc);
    return () => window.removeEventListener("keydown", onEsc);
  }, [onClose, closeable]);

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/40 sm:px-3 sm:py-6"
      onClick={() => closeable && onClose()}
    >
      <div
        className="bg-white rounded-t-2xl sm:rounded-2xl shadow-xl w-full sm:max-w-lg max-h-[96vh] overflow-y-auto overscroll-contain"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="px-5 sm:px-6 pt-5 pb-3 border-b border-gray-100 flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 id={titleId} className="font-serif text-lg text-gray-900">
              {title}
            </h2>
            {subtitle && (
              <p className="text-xs text-gray-500 mt-0.5">{subtitle}</p>
            )}
          </div>
          {closeable && (
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="shrink-0 text-gray-400 hover:text-gray-700 cursor-pointer"
            >
              <X size={16} />
            </button>
          )}
        </header>
        <div className="px-5 sm:px-6 py-4">{children}</div>
        <footer className="sticky bottom-0 px-5 sm:px-6 py-4 border-t border-gray-100 flex flex-wrap items-center justify-end gap-2 bg-white sm:rounded-b-2xl">
          {footer}
        </footer>
      </div>
    </div>
  );
}

export default ModalShell;

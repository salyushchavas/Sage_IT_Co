"use client";

import { useEffect, useRef } from "react";
import { FileText, X } from "lucide-react";

/**
 * The approver's read-only agreement preview: the website's copy of the
 * console approvals page's preview modal
 * (src/app/agreements/approvals/page.tsx, ApproverPreviewModal).
 *
 * Shows the agreement as page images (PNG, 110 DPI, no watermark) with
 * selecting, copying, cutting, right-click and dragging all blocked — a
 * deterrent only, screenshots still work. No PDF reaches the browser here,
 * so there is nothing to "save as".
 *
 * When `onScrolledToEnd` is given, it fires once the marker after the last
 * page comes into view inside the modal's own scroll area (or straight away
 * when a short agreement fits without scrolling). The Pending tab uses it to
 * unlock Approve / Request revision; it is checked in the browser only.
 */
export default function ApproverPreviewModal({
  pages,
  onClose,
  title,
  onScrolledToEnd,
}: {
  pages: string[];
  onClose: () => void;
  /** Defaults to "Agreement preview". */
  title?: string;
  onScrolledToEnd?: () => void;
}) {
  const scrollerRef = useRef<HTMLDivElement | null>(null);
  const sentinelRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const root = scrollerRef.current;
    const sentinel = sentinelRef.current;
    if (!root || !sentinel || !onScrolledToEnd) return;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) onScrolledToEnd();
      },
      // The modal's scroll area is the viewport, not the page behind it.
      { root, threshold: 0 },
    );
    io.observe(sentinel);
    return () => io.disconnect();
  }, [pages, onScrolledToEnd]);

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={title ?? "Agreement preview"}
      className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-2 sm:p-3"
      onClick={onClose}
    >
      <div
        className="bg-white w-full max-w-6xl h-[96vh] rounded-xl shadow-xl flex flex-col overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="shrink-0 flex items-center justify-between gap-3 px-4 py-3 border-b border-stone-100">
          <p className="min-w-0 text-sm font-bold text-sage-navy inline-flex items-center gap-1.5">
            <FileText size={14} className="shrink-0" />
            <span className="truncate">{title ?? "Agreement preview"}</span>
          </p>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close preview"
            className="shrink-0 text-gray-500 hover:text-sage-navy cursor-pointer"
          >
            <X size={16} />
          </button>
        </header>
        <div
          ref={scrollerRef}
          className="bg-stone-100 p-2 sm:p-4 flex-1 min-h-0 overflow-y-auto overscroll-contain space-y-3 select-none"
          style={{ userSelect: "none", WebkitUserSelect: "none", MozUserSelect: "none" }}
          onContextMenu={(e) => e.preventDefault()}
          onCopy={(e) => e.preventDefault()}
          onCut={(e) => e.preventDefault()}
          onDragStart={(e) => e.preventDefault()}
        >
          {pages.length === 0 ? (
            <p className="text-center text-xs text-gray-500 py-12">
              Nothing to preview.
            </p>
          ) : (
            <>
              {pages.map((b64, i) => (
                // eslint-disable-next-line @next/next/no-img-element
                <img
                  key={i}
                  src={`data:image/png;base64,${b64}`}
                  alt={`Agreement page ${i + 1}`}
                  draggable={false}
                  onDragStart={(e) => e.preventDefault()}
                  onContextMenu={(e) => e.preventDefault()}
                  className="block w-full rounded-md border border-stone-200 shadow-sm"
                  style={{ userSelect: "none", WebkitUserSelect: "none" }}
                />
              ))}
              {/* Read-to-end marker: drives the Approve / Request revision gate. */}
              <div ref={sentinelRef} aria-hidden className="h-2 w-full" />
            </>
          )}
        </div>
      </div>
    </div>
  );
}

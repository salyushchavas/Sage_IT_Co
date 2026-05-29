"use client";

import { motion, AnimatePresence } from "framer-motion";
import { X, Upload } from "lucide-react";

interface Props {
  isOpen: boolean;
  onClose: () => void;
  courseId: number;
  onUploaded: () => void;
}

/**
 * Stub: Spire's BulkUploadModal is a CSV/folder bulk-create
 * surface (~813 lines). Sage ships a "coming soon" placeholder for
 * now so the course content editor can mount cleanly; the full
 * bulk-upload flow lands in a follow-up batch.
 */
// eslint-disable-next-line @typescript-eslint/no-unused-vars
export function BulkUploadModal({ isOpen, onClose, courseId, onUploaded }: Props) {
  return (
    <AnimatePresence>
      {isOpen && (
        <motion.div
          initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
          className="fixed inset-0 z-50 flex items-center justify-center px-4 bg-black/40 backdrop-blur-sm"
          onClick={onClose}
        >
          <motion.div
            initial={{ scale: 0.95, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            exit={{ scale: 0.95, opacity: 0 }}
            transition={{ duration: 0.18 }}
            onClick={(e) => e.stopPropagation()}
            className="bg-white rounded-2xl shadow-xl w-full max-w-md p-6"
          >
            <div className="flex items-start justify-between mb-4">
              <h2 className="text-lg font-bold text-gray-900">Bulk Upload</h2>
              <button onClick={onClose} className="text-gray-400 hover:text-gray-600 cursor-pointer">
                <X size={18} />
              </button>
            </div>
            <div className="text-center py-8">
              <Upload size={32} className="mx-auto text-gray-300 mb-3" />
              <p className="text-sm text-gray-600 mb-2">Bulk module/lesson upload is coming soon.</p>
              <p className="text-xs text-gray-400">
                For now, use the &quot;Add Module&quot; and &quot;Add Lesson&quot; buttons in the content
                editor. Bulk CSV / folder import will be enabled in a future release.
              </p>
            </div>
            <button
              onClick={onClose}
              className="w-full px-4 py-2 rounded-lg text-sm font-semibold bg-sage-navy text-white hover:bg-sage-navy-deep transition cursor-pointer"
            >
              Got it
            </button>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

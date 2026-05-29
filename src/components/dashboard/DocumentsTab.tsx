"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { FileText, Loader2 } from "lucide-react";

import {
  listParticipantDocuments,
  type ParticipantDocument,
} from "@/lib/api";

export default function DocumentsTab() {
  const [docs, setDocs] = useState<ParticipantDocument[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    listParticipantDocuments()
      .then((d) => {
        if (!cancelled) setDocs(d);
      })
      .catch(() => {
        /* leave empty -- the "No documents on file" state handles it */
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (loading) {
    return (
      <div className="text-center py-10">
        <Loader2 size={20} className="animate-spin text-sage-navy inline" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Documents</h1>
      <p className="text-sm text-gray-500">
        Read-only view of your uploaded documents and their review status.
        Manage uploads from{" "}
        <Link
          href="/document-upload"
          className="text-sage-navy font-semibold hover:underline"
        >
          Document Upload
        </Link>
        .
      </p>
      <div className="rounded-xl border border-gray-200 bg-white divide-y divide-gray-100">
        {docs.length === 0 ? (
          <p className="px-4 py-3 text-sm text-gray-400 italic">
            No documents on file.
          </p>
        ) : (
          docs.map((d) => (
            <div
              key={d.id}
              className="px-4 py-3 flex items-center gap-3 text-sm"
            >
              <FileText size={14} className="text-gray-400" />
              <span className="flex-1 font-medium text-gray-800">
                {d.documentType}
              </span>
              <span className="text-xs text-gray-500">{d.fileName}</span>
              <span
                className={
                  "px-2 py-0.5 rounded-full text-[10px] font-bold " +
                  (d.reviewStatus === "APPROVED"
                    ? "bg-emerald-50 text-emerald-700"
                    : d.reviewStatus === "REJECTED"
                      ? "bg-red-50 text-red-700"
                      : d.reviewStatus === "NOT_APPLICABLE"
                        ? "bg-gray-100 text-gray-600"
                        : "bg-amber-50 text-amber-700")
                }
              >
                {d.reviewStatus}
              </span>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

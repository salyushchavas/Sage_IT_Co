"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import Image from "next/image";
import { motion } from "framer-motion";
import {
  CheckCircle2, XCircle, Loader2, Award, Calendar, User,
  BookOpen, Shield, ExternalLink,
} from "lucide-react";
import { verifyCertificate, type CertificateVerification } from "@/lib/api";
import { formatISTDate } from "@/lib/datetime";

/**
 * Public certificate verification page. Reachable without auth so a
 * recruiter can paste the URL and confirm the cert is real. Shows the
 * student name, course title, score, and issued date when valid; a
 * "not found" panel otherwise.
 *
 * The route is also where the QR code on the printed PDF resolves to.
 */
export default function VerifyCertificatePage({
  params,
}: {
  params: { certificateId: string };
}) {
  const [data, setData] = useState<CertificateVerification | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    verifyCertificate(params.certificateId)
      .then((d) => { if (!cancelled) setData(d); })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : "Verification failed");
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [params.certificateId]);

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      <header className="bg-white border-b border-gray-100">
        <div className="max-w-4xl mx-auto flex items-center justify-between px-6 py-4">
          <Link href="/" className="inline-flex items-center gap-2">
            <Image src="/sage_logo.png" alt="Sage IT Co" width={28} height={28} className="h-7 w-7 object-contain" />
            <span className="text-lg font-bold text-sage-navy">Sage IT Co</span>
          </Link>
          <Link
            href="/"
            className="text-sm text-gray-500 hover:text-sage-navy transition-colors"
          >
            Visit site →
          </Link>
        </div>
      </header>

      <main className="flex-1 flex flex-col items-center px-4 py-12">
        <div className="w-full max-w-xl">
          <div className="text-center mb-6">
            <Shield size={28} className="text-sage-navy mx-auto mb-2" />
            <p className="text-xs uppercase tracking-[2px] font-semibold text-gray-500">
              Certificate verification
            </p>
          </div>

          {loading ? (
            <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-10 text-center">
              <Loader2 size={28} className="animate-spin text-sage-navy mx-auto" />
              <p className="text-sm text-gray-500 mt-3">Looking up certificate…</p>
            </div>
          ) : error ? (
            <NotFoundCard certificateId={params.certificateId} />
          ) : data?.valid ? (
            <ValidCard data={data} />
          ) : (
            <NotFoundCard certificateId={params.certificateId} />
          )}

          <p className="mt-6 text-center text-xs text-gray-500 leading-relaxed">
            This page confirms whether the above person completed the stated
            course on the Sage IT Co platform. Verification data is served
            live from our records.
          </p>
        </div>
      </main>

      <footer className="bg-white border-t border-gray-100">
        <div className="max-w-4xl mx-auto px-6 py-4 text-center text-xs text-gray-500">
          © {new Date().getFullYear()} Sage IT Co · IT Consulting and Career Development
        </div>
      </footer>
    </div>
  );
}

function ValidCard({ data }: { data: CertificateVerification }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
      className="bg-white rounded-2xl border border-green-200 shadow-sm overflow-hidden"
    >
      <div className="bg-gradient-to-br from-green-50 to-white px-6 pt-6 pb-5 border-b border-green-100">
        <div className="inline-flex items-center gap-2 bg-green-100 text-green-700 text-xs font-bold uppercase tracking-wider px-3 py-1 rounded-full mb-3">
          <CheckCircle2 size={14} /> Verified
        </div>
        <h1 className="text-2xl font-bold text-gray-900">Certificate Verified</h1>
        <p className="text-sm text-gray-600 mt-1">
          This certificate is authentic and was issued by Sage IT Co.
        </p>
      </div>

      <div className="px-6 py-5 space-y-3">
        <DetailRow icon={User} label="Student" value={data.studentName ?? "—"} />
        <DetailRow icon={BookOpen} label="Course" value={data.courseTitle ?? "—"} />
        {data.finalScore != null && (
          <DetailRow
            icon={Award}
            label="Score"
            value={`${Math.round(data.finalScore)}%`}
          />
        )}
        {data.issuedAt && (
          <DetailRow
            icon={Calendar}
            label="Issued"
            value={formatISTDate(data.issuedAt)}
          />
        )}
        <DetailRow
          icon={Shield}
          label="Certificate ID"
          value={<code className="text-xs font-mono text-sage-navy">{data.certificateId}</code>}
        />
      </div>
    </motion.div>
  );
}

function NotFoundCard({ certificateId }: { certificateId: string }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
      className="bg-white rounded-2xl border border-red-200 shadow-sm overflow-hidden"
    >
      <div className="bg-gradient-to-br from-red-50 to-white px-6 pt-6 pb-5 border-b border-red-100">
        <div className="inline-flex items-center gap-2 bg-red-100 text-red-700 text-xs font-bold uppercase tracking-wider px-3 py-1 rounded-full mb-3">
          <XCircle size={14} /> Not found
        </div>
        <h1 className="text-2xl font-bold text-gray-900">Certificate Not Found</h1>
        <p className="text-sm text-gray-600 mt-1">
          The certificate ID you entered does not match any record in our system.
        </p>
      </div>

      <div className="px-6 py-5">
        <p className="text-sm text-gray-700">
          Looked up:{" "}
          <code className="text-xs font-mono bg-gray-100 px-2 py-1 rounded text-gray-700">
            {certificateId}
          </code>
        </p>
        <p className="text-xs text-gray-500 mt-3 leading-relaxed">
          Double-check the ID for typos. Sage certificate IDs use the format{" "}
          <code className="font-mono">SAGE-COURSE-INITIALS-DDMMYY</code>. If
          you believe this is an error, contact the issuing student or our
          support team.
        </p>
        <Link
          href="/support"
          className="inline-flex items-center gap-1.5 mt-4 text-sm font-semibold text-sage-navy hover:text-sage-navy-deep"
        >
          Contact support <ExternalLink size={13} />
        </Link>
      </div>
    </motion.div>
  );
}

function DetailRow({
  icon: Icon, label, value,
}: {
  icon: typeof User;
  label: string;
  value: React.ReactNode;
}) {
  return (
    <div className="flex items-start gap-3 py-2">
      <div className="w-8 h-8 rounded-lg bg-sage-navy/5 flex items-center justify-center shrink-0">
        <Icon size={15} className="text-sage-navy" />
      </div>
      <div className="flex-1 min-w-0">
        <p className="text-[11px] uppercase tracking-wider text-gray-500 font-semibold">
          {label}
        </p>
        <div className="text-base font-semibold text-gray-900 mt-0.5 break-words">
          {value}
        </div>
      </div>
    </div>
  );
}

"use client";

import { useEffect, useState, type ReactNode } from "react";
import { AlertCircle, CheckCircle2, Loader2, Save } from "lucide-react";

import {
  getParticipantProfile,
  updateParticipantProfile,
  type UserDTO,
} from "@/lib/api";

export default function ProfileTab() {
  const [profile, setProfile] = useState<UserDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [feedback, setFeedback] = useState("");
  const [error, setError] = useState("");
  const [fullName, setFullName] = useState("");
  const [phone, setPhone] = useState("");
  const [location, setLocation] = useState("");
  const [availability, setAvailability] = useState("");
  const [bio, setBio] = useState("");

  useEffect(() => {
    let cancelled = false;
    getParticipantProfile()
      .then((p) => {
        if (cancelled) return;
        setProfile(p);
        setFullName(p.fullName ?? "");
        setPhone(p.phone ?? "");
        setLocation(p.location ?? "");
        setAvailability(p.availability ?? "");
        setBio(p.bio ?? "");
      })
      .catch((err) => {
        if (!cancelled)
          setError(
            err instanceof Error ? err.message : "Couldn't load profile",
          );
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleSave = async () => {
    setSaving(true);
    setError("");
    setFeedback("");
    try {
      const updated = await updateParticipantProfile({
        fullName: fullName.trim() || undefined,
        phone,
        location,
        availability,
        bio,
      });
      setProfile(updated);
      setFeedback("Profile saved.");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't save profile");
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="text-center py-10">
        <Loader2 size={20} className="animate-spin text-sage-navy inline" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Profile</h1>

      <Section title="Account">
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-sm">
          <ReadOnlyRow label="Email" value={profile?.email} />
          <ReadOnlyRow
            label="Participant ID"
            value={profile?.participantId}
            mono
          />
          <ReadOnlyRow
            label="Enrolled"
            value={
              profile?.createdAt
                ? new Date(profile.createdAt).toLocaleDateString("en-IN", {
                    timeZone: "Asia/Kolkata",
                    dateStyle: "medium",
                  })
                : null
            }
          />
          <ReadOnlyRow label="Status" value={profile?.currentStatus} mono />
          <ReadOnlyRow
            label="Selected technology"
            value={profile?.selectedTechnology}
          />
        </div>
      </Section>

      <Section title="Editable details">
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <Input label="Full name" value={fullName} onChange={setFullName} />
          <Input label="Phone" value={phone} onChange={setPhone} />
          <Input label="Location" value={location} onChange={setLocation} />
          <Input
            label="Availability"
            value={availability}
            onChange={setAvailability}
          />
          <div className="sm:col-span-2">
            <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
              Bio
            </label>
            <textarea
              value={bio}
              onChange={(e) => setBio(e.target.value)}
              rows={3}
              className="w-full px-3 py-1.5 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
            />
          </div>
        </div>
      </Section>

      {error && (
        <p className="inline-flex items-center gap-1.5 text-sm text-red-700">
          <AlertCircle size={14} /> {error}
        </p>
      )}
      {feedback && (
        <p className="inline-flex items-center gap-1.5 text-sm text-emerald-700">
          <CheckCircle2 size={14} /> {feedback}
        </p>
      )}

      <div className="flex justify-end">
        <button
          type="button"
          onClick={handleSave}
          disabled={saving}
          className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-bold bg-sage-navy text-white hover:bg-sage-navy-deep disabled:opacity-60 cursor-pointer"
        >
          {saving ? (
            <Loader2 size={14} className="animate-spin" />
          ) : (
            <Save size={14} />
          )}
          {saving ? "Saving…" : "Save profile"}
        </button>
      </div>
    </div>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <p className="text-[11px] uppercase tracking-wider font-semibold text-gray-500 mb-1.5">
        {title}
      </p>
      <div className="rounded-2xl border border-gray-100 bg-white p-3 sm:p-4 shadow-sm">
        {children}
      </div>
    </div>
  );
}

function ReadOnlyRow({
  label,
  value,
  mono,
}: {
  label: string;
  value?: string | null;
  mono?: boolean;
}) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-[10px] uppercase tracking-wider font-semibold text-gray-500">
        {label}
      </span>
      <span
        className={
          mono
            ? "font-mono text-[13px] text-gray-800"
            : "text-[13px] text-gray-800"
        }
      >
        {value ?? "—"}
      </span>
    </div>
  );
}

function Input({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
}) {
  return (
    <div>
      <label className="block text-[11px] font-medium text-gray-600 mb-0.5">
        {label}
      </label>
      <input
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="w-full px-3 py-1.5 text-sm rounded-md border border-gray-200 focus:outline-none focus:border-sage-navy focus:ring-1 focus:ring-sage-navy"
      />
    </div>
  );
}

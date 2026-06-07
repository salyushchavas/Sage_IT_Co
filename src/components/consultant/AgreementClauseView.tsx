"use client";

import { AGREEMENT_SECTIONS } from "@/lib/agreement-sections";
import type {
  AgreementBlock,
  AgreementSegment,
} from "@/lib/api";

/**
 * Renders the REAL agreement clauses for one wizard section, parsed from
 * the master template (single source of truth), with the consultant's
 * entered values filling in live as they type. Consultant-editable
 * placeholders read from form state; non-editable ones (effective date,
 * rates, ERM block) from the per-app `values`; the consultant signature
 * renders inline once drawn.
 */

// fieldKey -> friendly label, derived from the section blueprint.
const FIELD_LABELS: Record<string, string> = (() => {
  const out: Record<string, string> = {};
  for (const s of AGREEMENT_SECTIONS) {
    for (const f of s.fields) out[f.key] = f.label;
  }
  return out;
})();

// Template placeholder names that don't match their entity field key 1:1.
const PLACEHOLDER_ALIAS: Record<string, string> = {
  participantFullLegalName: "consultantName",
  primaryEmail: "consultantEmail",
};

function fieldKeyFor(name: string): string {
  return PLACEHOLDER_ALIAS[name] ?? name;
}

function prettify(name: string): string {
  return name
    .replace(/([A-Z])/g, " $1")
    .replace(/^./, (c) => c.toUpperCase())
    .trim();
}

interface Props {
  blocks: AgreementBlock[];
  values: Record<string, string>;
  fields: Record<string, string>;
  signature: string | null;
}

export default function AgreementClauseView({
  blocks,
  values,
  fields,
  signature,
}: Props) {
  return (
    <div className="max-h-[60vh] lg:max-h-[70vh] overflow-y-auto pr-1 space-y-2.5">
      {blocks.map((b, i) => (
        <BlockView
          key={i}
          block={b}
          values={values}
          fields={fields}
          signature={signature}
        />
      ))}
    </div>
  );
}

function BlockView({
  block,
  values,
  fields,
  signature,
}: {
  block: AgreementBlock;
  values: Record<string, string>;
  fields: Record<string, string>;
  signature: string | null;
}) {
  const segs = (s: AgreementSegment[] | null | undefined) =>
    (s ?? []).map((seg, i) => (
      <SegmentView
        key={i}
        seg={seg}
        values={values}
        fields={fields}
        signature={signature}
      />
    ));

  if (block.kind === "heading") {
    if (block.level === 1) {
      return (
        <h3 className="font-serif text-base sm:text-lg font-bold text-sage-navy mt-5 first:mt-0 leading-snug">
          {segs(block.segments)}
        </h3>
      );
    }
    return (
      <h4 className="font-semibold text-sm text-sage-navy mt-4 leading-snug">
        {segs(block.segments)}
      </h4>
    );
  }

  if (block.kind === "table") {
    return (
      <div className="overflow-x-auto my-2">
        <table className="w-full text-[11px] border border-stone-300 border-collapse">
          <tbody>
            {(block.rows ?? []).map((row, ri) => (
              <tr key={ri}>
                {row.map((cell, ci) => (
                  <td
                    key={ci}
                    className="border border-stone-300 px-2 py-1 align-top text-gray-700"
                  >
                    {segs(cell)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  // paragraph
  if (!block.segments || block.segments.length === 0) {
    return <div className="h-1.5" />;
  }
  return (
    <p className="text-[13px] leading-relaxed text-gray-700 whitespace-pre-wrap">
      {segs(block.segments)}
    </p>
  );
}

function SegmentView({
  seg,
  values,
  fields,
  signature,
}: {
  seg: AgreementSegment;
  values: Record<string, string>;
  fields: Record<string, string>;
  signature: string | null;
}) {
  if (seg.kind === "text") {
    return <>{seg.text}</>;
  }

  const name = seg.name ?? "";
  const key = fieldKeyFor(name);

  // 1. Consultant-editable → live from form state.
  if (key in FIELD_LABELS) {
    const v = (fields[key] ?? "").trim();
    if (v) {
      return (
        <span className="font-semibold text-sage-navy whitespace-pre-wrap">
          {v}
        </span>
      );
    }
    return <Blank label={FIELD_LABELS[key]} />;
  }

  // 2. Consultant signature → inline image once drawn.
  if (name === "signatureImage") {
    if (signature) {
      return (
        <img
          src={signature}
          alt="Your signature"
          className="inline-block align-middle h-9 max-w-[180px] object-contain"
        />
      );
    }
    return <Blank label="your signature" />;
  }

  // 3. ERM signature → filled at countersignature.
  if (name === "ermSignatureImage") {
    return (
      <span className="text-gray-400 italic">[Sage IT signature]</span>
    );
  }

  // 4. Non-editable app value (effective date, rates, ERM block, …).
  const fromValues = (values[name] ?? "").trim();
  if (fromValues) {
    return <span className="font-semibold text-sage-navy">{fromValues}</span>;
  }

  // 5. Neutral blank.
  return <Blank label={prettify(name)} />;
}

function Blank({ label }: { label: string }) {
  return (
    <span className="inline-flex items-center px-1.5 rounded bg-sage-copper/10 text-sage-copper-deep border-b border-dashed border-sage-copper/60 text-[12px] font-medium">
      {label}
    </span>
  );
}

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
  primaryEmail: "consultantEmail",
};

function fieldKeyFor(name: string): string {
  return PLACEHOLDER_ALIAS[name] ?? name;
}

// Build W — compose the full legal name live from the structured parts.
function composeNameFromFields(fields: Record<string, string>): string {
  const first = (fields["firstName"] ?? "").trim();
  const middle = (fields["middleName"] ?? "").trim();
  const last = (fields["lastName"] ?? "").trim();
  return [first, middle, last].filter((p) => p.length > 0).join(" ").trim();
}

// Build W — assemble the US-format billing address live from the
// structured parts ("Line1[, Line2], City, ST ZIP").
function assembleAddressFromFields(fields: Record<string, string>): string {
  const line1 = (fields["addressLine1"] ?? "").trim();
  const line2 = (fields["addressLine2"] ?? "").trim();
  const city = (fields["addressCity"] ?? "").trim();
  const state = (fields["addressState"] ?? "").trim();
  const zip = (fields["addressZip"] ?? "").trim();
  let cityStateZip = city;
  if (state) cityStateZip = cityStateZip ? `${cityStateZip}, ${state}` : state;
  if (zip) cityStateZip = cityStateZip ? `${cityStateZip} ${zip}` : zip;
  return [line1, line2, cityStateZip].filter((p) => p.length > 0).join(", ").trim();
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
    // Build Y — the clause text is read-only + non-copyable (deterrent
    // only). Inputs are rendered elsewhere (the wizard's action rail), so
    // this never touches form controls.
    <div
      className="space-y-3 [&_p+p]:mt-3 select-none"
      onCopy={(e) => e.preventDefault()}
      onCut={(e) => e.preventDefault()}
      onContextMenu={(e) => e.preventDefault()}
      onDragStart={(e) => e.preventDefault()}
      style={{ userSelect: "none", WebkitUserSelect: "none", MozUserSelect: "none" }}
    >
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
        <h3
          className="text-[21px] sm:text-[23px] font-semibold mt-8 first:mt-0 leading-snug [text-wrap:balance]"
          style={{
            fontFamily: "var(--font-fraunces), ui-serif, Georgia, serif",
            color: "var(--navy)",
          }}
        >
          {segs(block.segments)}
        </h3>
      );
    }
    return (
      <h4
        className="text-[17.5px] font-semibold mt-6 leading-snug [text-wrap:balance]"
        style={{
          fontFamily: "var(--font-fraunces), ui-serif, Georgia, serif",
          color: "var(--navy)",
        }}
      >
        {segs(block.segments)}
      </h4>
    );
  }

  if (block.kind === "table") {
    return (
      <div className="overflow-x-auto my-4">
        <table
          className="w-full text-[13px] border-collapse"
          style={{ border: "1px solid var(--line)" }}
        >
          <tbody>
            {(block.rows ?? []).map((row, ri) => (
              <tr key={ri}>
                {row.map((cell, ci) => (
                  <td
                    key={ci}
                    className="px-3 py-2 align-top"
                    style={{ border: "1px solid var(--line)", color: "var(--ink)" }}
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
    return <div className="h-2" />;
  }
  return (
    <p
      className="text-[16.5px] leading-[1.7] whitespace-pre-wrap [text-wrap:pretty]"
      style={{
        fontFamily: "var(--font-newsreader), ui-serif, Georgia, serif",
        color: "var(--ink)",
      }}
    >
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

  // Build W — composed full legal name, live from the structured parts.
  if (name === "participantFullLegalName") {
    const full = composeNameFromFields(fields);
    return full ? <Filled value={full} /> : <Blank label="your full legal name" />;
  }

  // Build W — assembled US billing address, live from the parts.
  if (name === "residenceAddress") {
    const addr = assembleAddressFromFields(fields);
    return addr ? <Filled value={addr} /> : <Blank label="your address" />;
  }

  // Build W — work authorization is ERM-set; the EFFECTIVE value (custom
  // text when "Others") comes from the per-app values.
  if (name === "workAuthorizationCategory") {
    const eff =
      (values["workAuthorizationCategory"] ?? "").trim()
      || (fields["workAuthorizationCategory"] ?? "").trim();
    return eff ? <Filled value={eff} /> : <Blank label="work authorization" />;
  }

  // Build W — ERM signature date: blank (render nothing) until the ERM
  // countersigns, so no date shows under the unsigned ERM signature.
  if (name === "ermSignatureDate") {
    const v = (values["ermSignatureDate"] ?? "").trim();
    return v ? <Filled value={v} /> : <></>;
  }

  const key = fieldKeyFor(name);

  // 1. Consultant-editable → live from form state.
  if (key in FIELD_LABELS) {
    const v = (fields[key] ?? "").trim();
    if (v) {
      return <Filled value={v} />;
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
    return <span className="text-stone-400 italic">[Sage IT signature]</span>;
  }

  // 4. Non-editable app value (effective date, rates, ERM block, …).
  const fromValues = (values[name] ?? "").trim();
  if (fromValues) {
    return <Filled value={fromValues} />;
  }

  // 5. Neutral blank.
  return <Blank label={prettify(name)} />;
}

/** Filled inline value. Copper underline + copper-wash tie the delight moment to the brand. */
function Filled({ value }: { value: string }) {
  return (
    <span
      className="font-semibold whitespace-pre-wrap px-[3px] rounded-[3px]"
      style={{
        color: "var(--navy)",
        background: "var(--copper-wash)",
        borderBottom: "1.5px solid var(--copper)",
      }}
    >
      {value}
    </span>
  );
}

/** Empty required placeholder. Dashed copper underline + chip background. */
function Blank({ label }: { label: string }) {
  return (
    <span
      className="inline-flex items-center px-1.5 rounded-[3px] text-[14px] font-medium not-italic"
      style={{
        color: "var(--copper-deep)",
        background: "var(--copper-wash)",
        borderBottom: "1.5px dashed var(--copper)",
      }}
    >
      {label}
    </span>
  );
}

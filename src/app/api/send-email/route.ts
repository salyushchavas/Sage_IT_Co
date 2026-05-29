import { NextRequest, NextResponse } from "next/server";
import nodemailer from "nodemailer";

/**
 * Email relay endpoint. The Spring Boot backend on Railway POSTs
 * email payloads here; this route forwards them through Gmail
 * SMTP using the same credentials the contact form uses. Keeps
 * the SMTP password off Railway entirely -- it lives only in
 * Vercel.
 *
 * Auth: x-relay-secret header must match EMAIL_RELAY_SECRET (a
 * shared secret set on both Railway and Vercel). Without that,
 * anyone with the public URL could spam through Sage's Gmail
 * quota.
 */

const RELAY_SECRET = process.env.EMAIL_RELAY_SECRET;
const SMTP_EMAIL = process.env.SMTP_EMAIL;
const SMTP_PASSWORD = process.env.SMTP_PASSWORD;

// Singleton -- nodemailer reuses the connection across requests.
const transporter = nodemailer.createTransport({
  service: "gmail",
  auth: {
    user: SMTP_EMAIL,
    pass: SMTP_PASSWORD,
  },
});

interface RelayPayload {
  to: string;
  subject: string;
  html?: string;
  text?: string;
  from?: string;
  replyTo?: string;
}

export async function POST(req: NextRequest) {
  // Auth -- shared secret only the backend knows.
  const supplied = req.headers.get("x-relay-secret");
  if (!RELAY_SECRET) {
    console.error("Email relay: EMAIL_RELAY_SECRET is not set on this deployment");
    return NextResponse.json(
      { error: "Relay not configured" },
      { status: 503 },
    );
  }
  if (supplied !== RELAY_SECRET) {
    console.warn("Email relay: unauthorized attempt");
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  let payload: RelayPayload;
  try {
    payload = (await req.json()) as RelayPayload;
  } catch {
    return NextResponse.json({ error: "Invalid JSON body" }, { status: 400 });
  }

  const { to, subject, html, text, from, replyTo } = payload;

  if (!to || !subject || (!html && !text)) {
    return NextResponse.json(
      { error: "Missing required fields: to, subject, and html or text" },
      { status: 400 },
    );
  }

  try {
    const info = await transporter.sendMail({
      from: from ?? `"Sage IT Co" <${SMTP_EMAIL}>`,
      to,
      subject,
      text,
      html,
      replyTo,
    });

    console.log(`Email relay: sent to ${to}, messageId=${info.messageId}`);

    return NextResponse.json({
      success: true,
      messageId: info.messageId,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Failed to send email";
    console.error("Email relay error:", message);
    return NextResponse.json({ error: message }, { status: 500 });
  }
}

export async function GET() {
  return NextResponse.json({ error: "Method not allowed" }, { status: 405 });
}

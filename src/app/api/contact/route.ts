import { NextRequest, NextResponse } from "next/server";
import nodemailer from "nodemailer";

// One address only: a comma or semicolon list would let one request email
// many strangers from the company account.
const EMAIL_RE = /^[^\s@,;<>"']+@[^\s@,;<>"']+\.[^\s@,;<>"']+$/;

// Everything a visitor types goes into HTML emails sent as Sage IT.
function esc(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function field(value: unknown, max: number): string {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}

// Best effort per server instance: at most 5 messages per address per 10 minutes.
const WINDOW_MS = 10 * 60 * 1000;
const MAX_PER_WINDOW = 5;
const recent = new Map<string, number[]>();

function allowed(ip: string): boolean {
  const now = Date.now();
  const hits = (recent.get(ip) ?? []).filter((t) => now - t < WINDOW_MS);
  if (hits.length >= MAX_PER_WINDOW) {
    recent.set(ip, hits);
    return false;
  }
  hits.push(now);
  recent.set(ip, hits);
  if (recent.size > 5000) recent.clear();
  return true;
}

export async function POST(req: NextRequest) {
  try {
    const ip = req.ip ?? req.headers.get("x-real-ip") ?? req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ?? "unknown";
    if (!allowed(ip)) {
      return NextResponse.json(
        { error: "Too many messages. Please try again in a few minutes." },
        { status: 429 }
      );
    }

    const body = await req.json().catch(() => null);
    const firstName = field(body?.firstName, 100);
    const lastName = field(body?.lastName, 100);
    const email = field(body?.email, 254);
    const company = field(body?.company, 150);
    const service = field(body?.service, 100);
    const message = field(body?.message, 5000);

    if (!firstName || !lastName || !email || !message) {
      return NextResponse.json(
        { error: "First name, last name, email, and message are required." },
        { status: 400 }
      );
    }
    if (!EMAIL_RE.test(email)) {
      return NextResponse.json({ error: "Enter a single valid email address." }, { status: 400 });
    }

    const name = esc(`${firstName} ${lastName}`);
    const transporter = nodemailer.createTransport({
      service: "gmail",
      auth: {
        user: process.env.SMTP_EMAIL,
        pass: process.env.SMTP_PASSWORD,
      },
    });

    const mailOptions = {
      from: `"Sage IT Website" <${process.env.SMTP_EMAIL}>`,
      to: process.env.CONTACT_RECEIVE_EMAIL,
      replyTo: email,
      subject: `New Contact Form Submission — ${`${firstName} ${lastName}`.replace(/[\r\n]+/g, " ")}`,
      html: `
        <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; background: #0a0a1a; color: #e4e4e7; padding: 32px; border-radius: 16px;">
          <div style="text-align: center; margin-bottom: 24px;">
            <h1 style="color: #1B2A5C; margin: 0; font-size: 24px;">New Contact Inquiry</h1>
            <p style="color: #71717a; font-size: 14px; margin-top: 4px;">Sage IT Website — Contact Form</p>
          </div>

          <table style="width: 100%; border-collapse: collapse; margin-bottom: 24px;">
            <tr>
              <td style="padding: 12px 16px; border-bottom: 1px solid #27272a; color: #a1a1aa; font-size: 13px; width: 120px;">Name</td>
              <td style="padding: 12px 16px; border-bottom: 1px solid #27272a; color: #ffffff; font-size: 14px; font-weight: 600;">${name}</td>
            </tr>
            <tr>
              <td style="padding: 12px 16px; border-bottom: 1px solid #27272a; color: #a1a1aa; font-size: 13px;">Email</td>
              <td style="padding: 12px 16px; border-bottom: 1px solid #27272a; color: #1B2A5C; font-size: 14px;">
                <a href="mailto:${esc(email)}" style="color: #1B2A5C; text-decoration: none;">${esc(email)}</a>
              </td>
            </tr>
            ${company ? `
            <tr>
              <td style="padding: 12px 16px; border-bottom: 1px solid #27272a; color: #a1a1aa; font-size: 13px;">Company</td>
              <td style="padding: 12px 16px; border-bottom: 1px solid #27272a; color: #ffffff; font-size: 14px;">${esc(company)}</td>
            </tr>
            ` : ""}
            ${service ? `
            <tr>
              <td style="padding: 12px 16px; border-bottom: 1px solid #27272a; color: #a1a1aa; font-size: 13px;">Service</td>
              <td style="padding: 12px 16px; border-bottom: 1px solid #27272a; color: #C87D5C; font-size: 14px;">${esc(service)}</td>
            </tr>
            ` : ""}
          </table>

          <div style="background: #111127; border-radius: 12px; padding: 20px; margin-bottom: 24px;">
            <p style="color: #a1a1aa; font-size: 12px; text-transform: uppercase; letter-spacing: 1px; margin: 0 0 8px 0;">Message</p>
            <p style="color: #e4e4e7; font-size: 14px; line-height: 1.7; margin: 0; white-space: pre-wrap;">${esc(message)}</p>
          </div>

          <div style="text-align: center; padding-top: 16px; border-top: 1px solid #27272a;">
            <p style="color: #52525b; font-size: 12px; margin: 0;">This email was sent from the Sage IT website contact form.</p>
          </div>
        </div>
      `,
    };

    // Send confirmation email to the user
    const confirmationOptions = {
      from: `"Sage IT" <${process.env.SMTP_EMAIL}>`,
      to: email,
      subject: "Thank you for contacting Sage IT",
      html: `
        <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; background: #0a0a1a; color: #e4e4e7; padding: 32px; border-radius: 16px;">
          <div style="text-align: center; margin-bottom: 24px;">
            <h1 style="color: #1B2A5C; margin: 0; font-size: 24px;">Thank You, ${esc(firstName)}!</h1>
          </div>

          <p style="color: #a1a1aa; font-size: 14px; line-height: 1.7;">
            We have received your message and our team will get back to you within 24 hours.
          </p>

          <p style="color: #a1a1aa; font-size: 14px; line-height: 1.7;">
            In the meantime, feel free to explore our <a href="https://sageitco.com/resources" style="color: #1B2A5C; text-decoration: none;">resources</a>
            or hear from our learners in our <a href="https://sageitco.com/testimonials" style="color: #1B2A5C; text-decoration: none;">testimonials</a>.
          </p>

          <div style="text-align: center; margin-top: 24px; padding-top: 16px; border-top: 1px solid #27272a;">
            <p style="color: #C87D5C; font-weight: 600; font-size: 16px; margin: 0;">Sage IT</p>
            <p style="color: #52525b; font-size: 12px; margin: 4px 0 0 0;">Engineering Intelligence. Empowering Growth.</p>
          </div>
        </div>
      `,
    };

    await transporter.sendMail(mailOptions);
    await transporter.sendMail(confirmationOptions);

    return NextResponse.json({ success: true, message: "Email sent successfully" });
  } catch (error) {
    console.error("Email send error:", error);
    return NextResponse.json(
      { error: "Failed to send email. Please try again later." },
      { status: 500 }
    );
  }
}

import { NextRequest, NextResponse } from "next/server";
import nodemailer from "nodemailer";

export async function POST(req: NextRequest) {
  try {
    const body = await req.json();
    const { firstName, lastName, email, company, service, message } = body;

    if (!firstName || !lastName || !email || !message) {
      return NextResponse.json(
        { error: "First name, last name, email, and message are required." },
        { status: 400 }
      );
    }

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
      subject: `New Contact Form Submission — ${firstName} ${lastName}`,
      html: `
        <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; background: #0a0a1a; color: #e4e4e7; padding: 32px; border-radius: 16px;">
          <div style="text-align: center; margin-bottom: 24px;">
            <h1 style="color: #00d4ff; margin: 0; font-size: 24px;">New Contact Inquiry</h1>
            <p style="color: #71717a; font-size: 14px; margin-top: 4px;">Sage IT Website — Contact Form</p>
          </div>

          <table style="width: 100%; border-collapse: collapse; margin-bottom: 24px;">
            <tr>
              <td style="padding: 12px 16px; border-bottom: 1px solid #27272a; color: #a1a1aa; font-size: 13px; width: 120px;">Name</td>
              <td style="padding: 12px 16px; border-bottom: 1px solid #27272a; color: #ffffff; font-size: 14px; font-weight: 600;">${firstName} ${lastName}</td>
            </tr>
            <tr>
              <td style="padding: 12px 16px; border-bottom: 1px solid #27272a; color: #a1a1aa; font-size: 13px;">Email</td>
              <td style="padding: 12px 16px; border-bottom: 1px solid #27272a; color: #00d4ff; font-size: 14px;">
                <a href="mailto:${email}" style="color: #00d4ff; text-decoration: none;">${email}</a>
              </td>
            </tr>
            ${company ? `
            <tr>
              <td style="padding: 12px 16px; border-bottom: 1px solid #27272a; color: #a1a1aa; font-size: 13px;">Company</td>
              <td style="padding: 12px 16px; border-bottom: 1px solid #27272a; color: #ffffff; font-size: 14px;">${company}</td>
            </tr>
            ` : ""}
            ${service ? `
            <tr>
              <td style="padding: 12px 16px; border-bottom: 1px solid #27272a; color: #a1a1aa; font-size: 13px;">Service</td>
              <td style="padding: 12px 16px; border-bottom: 1px solid #27272a; color: #7c3aed; font-size: 14px;">${service}</td>
            </tr>
            ` : ""}
          </table>

          <div style="background: #111127; border-radius: 12px; padding: 20px; margin-bottom: 24px;">
            <p style="color: #a1a1aa; font-size: 12px; text-transform: uppercase; letter-spacing: 1px; margin: 0 0 8px 0;">Message</p>
            <p style="color: #e4e4e7; font-size: 14px; line-height: 1.7; margin: 0; white-space: pre-wrap;">${message}</p>
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
            <h1 style="color: #00d4ff; margin: 0; font-size: 24px;">Thank You, ${firstName}!</h1>
          </div>

          <p style="color: #a1a1aa; font-size: 14px; line-height: 1.7;">
            We have received your message and our team will get back to you within 24 hours.
          </p>

          <p style="color: #a1a1aa; font-size: 14px; line-height: 1.7;">
            In the meantime, feel free to explore our <a href="https://sageitco.com/services" style="color: #00d4ff; text-decoration: none;">services</a>
            or check out our <a href="https://sageitco.com/portfolio" style="color: #00d4ff; text-decoration: none;">portfolio</a>.
          </p>

          <div style="text-align: center; margin-top: 24px; padding-top: 16px; border-top: 1px solid #27272a;">
            <p style="color: #7c3aed; font-weight: 600; font-size: 16px; margin: 0;">Sage IT</p>
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

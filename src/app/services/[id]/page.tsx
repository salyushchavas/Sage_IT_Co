"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import {
  Briefcase, Loader2, AlertCircle, BookOpen, ChevronLeft, ShoppingCart, ArrowRight, ShieldCheck, MessageSquare,
} from "lucide-react";
import { ContactSalesModal } from "@/components/sales/ContactSalesModal";
import { useToast } from "@/components/ui/Toast";
import { useAuth } from "@/lib/auth-context";
import {
  getService, getCourseLessons, getCourseModules, addToCart, enroll, getEnrollments,
} from "@/lib/api";
import { friendlyEnrollmentError } from "@/lib/utils";
import { LessonItem } from "@/components/courses/LessonItem";
import { VideoPlayer } from "@/components/courses/VideoPlayer";

interface ServiceData {
  id: number;
  title: string;
  slug: string;
  description: string | null;
  shortDescription: string | null;
  type: string;
  price: number;
  isFree: boolean;
  category: string | null;
  thumbnailUrl: string | null;
  lessonsCount: number;
  trainer: { id: number; fullName: string; email: string; avatarUrl: string | null } | null;
  instructor: { id: number; fullName: string; email: string; avatarUrl: string | null } | null;
}

interface LessonData {
  id: number;
  courseId: number;
  title: string;
  description: string | null;
  videoUrl: string | null;
  orderIndex: number;
  durationMinutes: number | null;
  isFree: boolean;
}

interface ModuleData {
  id: number;
  courseId: number;
  title: string;
  description: string | null;
  orderIndex: number;
  lessons: LessonData[];
}

export default function ServiceDetailPage({ params }: { params: { id: string } }) {
  const { id } = params;
  const router = useRouter();
  const { user, isAuthenticated } = useAuth();
  const isAdmin = user?.role?.toUpperCase() === "ADMIN";
  const { toast } = useToast();

  const [service, setService] = useState<ServiceData | null>(null);
  const [lessons, setLessons] = useState<LessonData[]>([]);
  const [modules, setModules] = useState<ModuleData[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [enrolling, setEnrolling] = useState(false);
  const [enrolled, setEnrolled] = useState(false);
  const [selectedLessonId, setSelectedLessonId] = useState<number | null>(null);
  const [enrollMsg, setEnrollMsg] = useState("");
  const [showContactSales, setShowContactSales] = useState(false);

  useEffect(() => {
    const fetchData = async () => {
      setLoading(true);
      try {
        const data = (await getService(id)) as ServiceData;

        // If somehow this is actually a regular course, redirect to /courses
        if (data?.type === "COURSE") {
          router.replace(`/courses/${id}`);
          return;
        }

        setService(data);
        const lessonData = await getCourseLessons(id);
        setLessons((lessonData || []) as LessonData[]);
        const moduleData = await getCourseModules(id);
        setModules((moduleData || []) as ModuleData[]);

        // Authoritative enrollment check — same approach as the courses
        // page. The previous "any non-free lesson has a videoUrl" heuristic
        // was unreliable since seed data sometimes carries videoUrls before
        // the user has paid.
        try {
          const myEnrollments = await getEnrollments() as Array<{ id: number }>;
          if ((myEnrollments ?? []).some((c) => c.id === Number(id))) {
            setEnrolled(true);
          }
        } catch {
          // Anonymous viewer — leave as not enrolled.
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to load service");
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, [id, router]);

  const handleEnroll = async () => {
    if (!isAuthenticated) {
      router.push(`/login?redirect=/services/${id}`);
      return;
    }
    setEnrolling(true);
    setEnrollMsg("");
    try {
      await enroll(Number(id));
      setEnrollMsg("Enrolled successfully!");
      setEnrolled(true);
      const lessonData = await getCourseLessons(id);
      setLessons((lessonData || []) as LessonData[]);
    } catch (err) {
      const msg = friendlyEnrollmentError(err);
      setEnrollMsg(msg);
      if (msg.startsWith("You're already enrolled")) setEnrolled(true);
    } finally {
      setEnrolling(false);
    }
  };

  const handleAddToCart = async () => {
    if (!isAuthenticated) {
      router.push(`/login?redirect=/services/${id}`);
      return;
    }
    try {
      await addToCart(Number(id));
      toast("cart", "Service added to cart!");
    } catch (err) {
      const msg = friendlyEnrollmentError(err);
      toast(msg.startsWith("You're already enrolled") ? "info" : "error", msg);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-screen pt-24">
        <Loader2 className="animate-spin text-sage-navy" size={32} />
      </div>
    );
  }

  if (error || !service) {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen pt-24 px-6">
        <AlertCircle size={48} className="text-red-400 mb-4" />
        <h2 className="text-xl font-semibold text-gray-900 mb-2">Service not found</h2>
        <p className="text-gray-500 mb-6">{error || "This service may have been removed."}</p>
        <Link
          href="/services"
          className="inline-flex items-center gap-2 bg-sage-navy text-white px-5 py-2.5 rounded-full text-sm font-semibold hover:bg-sage-navy-deep"
        >
          <ChevronLeft size={16} /> Back to Services
        </Link>
      </div>
    );
  }

  // Bucket lessons under their modules; orphans (no module) under "Other"
  const lessonIdsInModules = new Set(modules.flatMap((m) => m.lessons.map((l) => l.id)));
  const orphanLessons = lessons.filter((l) => !lessonIdsInModules.has(l.id));

  const trainerName =
    service.trainer?.fullName ?? service.instructor?.fullName ?? "Sage IT Co Trainer";

  const renderLesson = (lesson: LessonData, idx: number) => (
    <div key={lesson.id}>
      <LessonItem
        id={lesson.id}
        title={lesson.title}
        description={lesson.description}
        orderIndex={lesson.orderIndex}
        durationMinutes={lesson.durationMinutes}
        isFree={lesson.isFree}
        videoUrl={lesson.videoUrl}
        canManage={false}
        canComplete={enrolled}
        index={idx}
        onClick={() =>
          setSelectedLessonId(selectedLessonId === lesson.id ? null : lesson.id)
        }
      />
      {selectedLessonId === lesson.id && (lesson.isFree || lesson.videoUrl) && (
        <div className="mt-2 ml-13">
          <VideoPlayer
            videoUrl={lesson.videoUrl}
            title={lesson.title}
            isFree={lesson.isFree}
          />
        </div>
      )}
    </div>
  );

  return (
    <section className="mx-auto max-w-7xl px-6 pt-28 pb-20">
      <motion.div initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }}>
        <Link
          href="/services"
          className="inline-flex items-center gap-1 text-sm text-sage-navy hover:text-sage-navy-deep mb-6"
        >
          <ChevronLeft size={14} /> All Services
        </Link>

        <div className="grid lg:grid-cols-3 gap-10">
          {/* Main */}
          <div className="lg:col-span-2 space-y-6">
            <div>
              <span className="inline-block text-[10px] font-semibold uppercase tracking-wide px-2.5 py-1 rounded-full bg-sage-navy text-white mb-3">
                Service
              </span>
              <h1 className="text-3xl sm:text-4xl font-bold text-gray-900">
                {service.title}
              </h1>
              {service.shortDescription && (
                <p className="text-lg text-gray-600 mt-3">{service.shortDescription}</p>
              )}
            </div>

            {service.description && (
              <p className="text-gray-700 leading-relaxed whitespace-pre-wrap">
                {service.description}
              </p>
            )}

            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-full bg-sage-navy text-white flex items-center justify-center text-sm font-bold">
                {trainerName.charAt(0)}
              </div>
              <div>
                <p className="text-sm font-semibold text-gray-900">{trainerName}</p>
                <p className="text-xs text-gray-500">Trainer</p>
              </div>
            </div>
          </div>

          {/* Sidebar — price + enroll */}
          <div className="lg:col-span-1">
            <div className="bg-white rounded-2xl shadow-lg border border-gray-100 p-6 lg:sticky lg:top-28">
              <div className="text-3xl font-bold text-gray-900 mb-1">
                {service.isFree || service.price <= 0
                  ? "Free"
                  : `₹${service.price.toLocaleString("en-IN")}`}
              </div>
              <p className="text-sm text-gray-500 mb-6">
                {service.isFree ? "No payment required" : "One-time payment"}
              </p>

              {isAdmin ? (
                // Admin supervises — no enrollment, full access by role.
                <div className="w-full py-3 rounded-xl bg-amber-50 border border-amber-200 text-amber-800 text-sm font-medium flex items-center justify-center gap-2">
                  <ShieldCheck size={16} /> Admin Access
                </div>
              ) : enrolled ? (
                <button
                  onClick={() => document.getElementById("service-content")?.scrollIntoView({ behavior: "smooth" })}
                  className="w-full py-3 rounded-xl bg-sage-navy text-white text-sm font-semibold hover:bg-sage-navy-deep transition flex items-center justify-center gap-2"
                >
                  <BookOpen size={16} /> Continue Learning
                </button>
              ) : service.isFree || service.price <= 0 ? (
                <button
                  onClick={handleEnroll}
                  disabled={enrolling}
                  className="w-full py-3 rounded-xl bg-sage-navy text-white text-sm font-semibold hover:bg-sage-navy-deep transition disabled:opacity-50 flex items-center justify-center gap-2"
                >
                  {enrolling ? (
                    <>
                      <Loader2 size={16} className="animate-spin" /> Enrolling...
                    </>
                  ) : (
                    "Enroll Now"
                  )}
                </button>
              ) : (
                <>
                  <button
                    onClick={handleAddToCart}
                    className="w-full py-3 rounded-xl bg-sage-navy text-white text-sm font-semibold hover:bg-sage-navy-deep transition flex items-center justify-center gap-2"
                  >
                    <ShoppingCart size={16} /> Add to Cart
                  </button>
                  <button
                    onClick={() => {
                      if (!isAuthenticated) { router.push(`/login?redirect=/services/${id}`); return; }
                      setShowContactSales(true);
                    }}
                    className="w-full mt-2 py-3 rounded-xl border-2 border-sage-navy text-sage-navy text-sm font-semibold hover:bg-sage-navy/5 transition flex items-center justify-center gap-2 cursor-pointer"
                  >
                    <MessageSquare size={16} /> Contact Sales
                  </button>
                  <p className="text-[11px] text-gray-500 mt-2 text-center">
                    or contact our team for custom pricing and bundle offers
                  </p>
                </>
              )}

              {enrollMsg && (
                <p
                  className={`text-xs mt-3 text-center ${
                    enrollMsg.includes("success") || enrollMsg.startsWith("You're already enrolled")
                      ? "text-sage-navy"
                      : "text-red-500"
                  }`}
                >
                  {enrollMsg}
                </p>
              )}

              <div className="mt-6 space-y-2 text-sm text-gray-600">
                {service.category && (
                  <p>
                    Category: <span className="font-medium text-gray-900">{service.category}</span>
                  </p>
                )}
                <p>
                  Lessons:{" "}
                  <span className="font-medium text-gray-900">
                    {service.lessonsCount ?? lessons.length}
                  </span>
                </p>
              </div>

              {!enrolled && !service.isFree && service.price > 0 && (
                <Link
                  href="/cart"
                  className="mt-3 w-full py-2.5 rounded-xl border border-gray-200 text-gray-700 text-sm font-medium hover:bg-gray-50 transition flex items-center justify-center gap-1"
                >
                  Go to Cart <ArrowRight size={14} />
                </Link>
              )}
            </div>
          </div>
        </div>

        {/* Content */}
        <motion.div
          id="service-content"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.2 }}
          className="mt-12"
        >
          <h2 className="text-2xl font-bold text-gray-900 mb-6 flex items-center gap-2">
            <Briefcase size={20} className="text-sage-navy" /> What's included
          </h2>

          {modules.length === 0 && lessons.length === 0 ? (
            <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-12 text-center">
              <p className="text-sm text-gray-500">Content coming soon.</p>
            </div>
          ) : (
            <div className="space-y-6">
              {modules
                .sort((a, b) => a.orderIndex - b.orderIndex)
                .map((m) => (
                  <div
                    key={m.id}
                    className="bg-white rounded-2xl border border-gray-100 shadow-sm p-5"
                  >
                    <h3 className="font-semibold text-gray-900 mb-1">{m.title}</h3>
                    {m.description && (
                      <p className="text-sm text-gray-500 mb-3">{m.description}</p>
                    )}
                    <div className="space-y-2">
                      {m.lessons
                        .sort((a, b) => a.orderIndex - b.orderIndex)
                        .map((l, i) => renderLesson(l, i))}
                    </div>
                  </div>
                ))}

              {orphanLessons.length > 0 && (
                <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-5">
                  <h3 className="font-semibold text-gray-900 mb-3">Lessons</h3>
                  <div className="space-y-2">
                    {orphanLessons.map((l, i) => renderLesson(l, i))}
                  </div>
                </div>
              )}
            </div>
          )}
        </motion.div>
      </motion.div>

      <ContactSalesModal
        isOpen={showContactSales}
        onClose={() => setShowContactSales(false)}
        courseId={service.id}
        courseTitle={service.title}
        listedPrice={service.price}
      />
    </section>
  );
}

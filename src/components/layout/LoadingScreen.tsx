"use client";

import { AnimatePresence, motion } from "framer-motion";
import Image from "next/image";
import { useEffect, useState } from "react";

export default function LoadingScreen() {
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const timer = setTimeout(() => setLoading(false), 1500);
    return () => clearTimeout(timer);
  }, []);

  return (
    <AnimatePresence>
      {loading && (
        <motion.div
          className="fixed inset-0 z-[200] bg-background flex items-center justify-center"
          exit={{ opacity: 0 }}
          transition={{ duration: 0.5 }}
        >
          <div className="text-center">
            <motion.div
              className="logo-glow relative w-24 h-24 mx-auto mb-6"
              animate={{ rotate: [0, 10, -10, 0], scale: [1, 1.05, 1] }}
              transition={{ duration: 1.5, repeat: Infinity }}
            >
              <Image
                src="/sage_logo.png"
                alt="Sage IT Co"
                fill
                priority
                className="object-contain drop-shadow-[0_0_25px_rgba(15,81,50,0.4)]"
              />
            </motion.div>
            <motion.div
              className="h-1 w-32 mx-auto rounded-full overflow-hidden bg-zinc-200"
            >
              <motion.div
                className="h-full bg-gradient-to-r from-sage-green to-sage-gold rounded-full"
                initial={{ width: "0%" }}
                animate={{ width: "100%" }}
                transition={{ duration: 1.3, ease: "easeInOut" }}
              />
            </motion.div>
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

"use client";

import { useRef, useMemo } from "react";
import { Canvas, useFrame } from "@react-three/fiber";
import * as THREE from "three";

function Nodes() {
  const groupRef = useRef<THREE.Group>(null);
  const count = 80;
  const connectionDistance = 2.5;

  const { positions, velocities } = useMemo(() => {
    const positions = new Float32Array(count * 3);
    const velocities = new Float32Array(count * 3);
    for (let i = 0; i < count; i++) {
      positions[i * 3] = (Math.random() - 0.5) * 8;
      positions[i * 3 + 1] = (Math.random() - 0.5) * 8;
      positions[i * 3 + 2] = (Math.random() - 0.5) * 8;
      velocities[i * 3] = (Math.random() - 0.5) * 0.005;
      velocities[i * 3 + 1] = (Math.random() - 0.5) * 0.005;
      velocities[i * 3 + 2] = (Math.random() - 0.5) * 0.005;
    }
    return { positions, velocities };
  }, []);

  const pointsRef = useRef<THREE.Points>(null);
  const linesRef = useRef<THREE.LineSegments>(null);

  const linePositions = useMemo(() => new Float32Array(count * count * 6), []);
  const lineColors = useMemo(() => new Float32Array(count * count * 6), []);

  useFrame(() => {
    // Update node positions
    for (let i = 0; i < count; i++) {
      for (let j = 0; j < 3; j++) {
        positions[i * 3 + j] += velocities[i * 3 + j];
        if (Math.abs(positions[i * 3 + j]) > 4) {
          velocities[i * 3 + j] *= -1;
        }
      }
    }

    if (pointsRef.current) {
      (pointsRef.current.geometry.attributes.position as THREE.BufferAttribute).needsUpdate = true;
    }

    // Update connections
    let lineIndex = 0;
    for (let i = 0; i < count; i++) {
      for (let j = i + 1; j < count; j++) {
        const dx = positions[i * 3] - positions[j * 3];
        const dy = positions[i * 3 + 1] - positions[j * 3 + 1];
        const dz = positions[i * 3 + 2] - positions[j * 3 + 2];
        const dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist < connectionDistance) {
          const alpha = 1 - dist / connectionDistance;
          linePositions[lineIndex * 6] = positions[i * 3];
          linePositions[lineIndex * 6 + 1] = positions[i * 3 + 1];
          linePositions[lineIndex * 6 + 2] = positions[i * 3 + 2];
          linePositions[lineIndex * 6 + 3] = positions[j * 3];
          linePositions[lineIndex * 6 + 4] = positions[j * 3 + 1];
          linePositions[lineIndex * 6 + 5] = positions[j * 3 + 2];

          // Forest green to mustard gold gradient
          // #0F5132 = rgb(0.059, 0.318, 0.196)
          lineColors[lineIndex * 6] = 0.059 * alpha;
          lineColors[lineIndex * 6 + 1] = 0.318 * alpha;
          lineColors[lineIndex * 6 + 2] = 0.196 * alpha;
          // #D4A017 = rgb(0.831, 0.627, 0.090)
          lineColors[lineIndex * 6 + 3] = 0.831 * alpha;
          lineColors[lineIndex * 6 + 4] = 0.627 * alpha;
          lineColors[lineIndex * 6 + 5] = 0.090 * alpha;

          lineIndex++;
        }
      }
    }

    if (linesRef.current) {
      linesRef.current.geometry.setDrawRange(0, lineIndex * 2);
      (linesRef.current.geometry.attributes.position as THREE.BufferAttribute).needsUpdate = true;
      (linesRef.current.geometry.attributes.color as THREE.BufferAttribute).needsUpdate = true;
    }

    if (groupRef.current) {
      groupRef.current.rotation.y += 0.001;
      groupRef.current.rotation.x += 0.0005;
    }
  });

  return (
    <group ref={groupRef}>
      {/* Nodes */}
      <points ref={pointsRef}>
        <bufferGeometry>
          <bufferAttribute
            attach="attributes-position"
            args={[positions, 3]}
          />
        </bufferGeometry>
        <pointsMaterial size={0.06} color="#0F5132" transparent opacity={0.8} sizeAttenuation />
      </points>

      {/* Connections */}
      <lineSegments ref={linesRef}>
        <bufferGeometry>
          <bufferAttribute
            attach="attributes-position"
            args={[linePositions, 3]}
          />
          <bufferAttribute
            attach="attributes-color"
            args={[lineColors, 3]}
          />
        </bufferGeometry>
        <lineBasicMaterial vertexColors transparent opacity={0.4} />
      </lineSegments>
    </group>
  );
}

export default function NeuralNetwork() {
  return (
    <div className="absolute inset-0 z-0">
      <Canvas
        camera={{ position: [0, 0, 6], fov: 60 }}
        gl={{ antialias: true, alpha: true }}
        style={{ background: "transparent" }}
      >
        <ambientLight intensity={0.5} />
        <Nodes />
      </Canvas>
    </div>
  );
}

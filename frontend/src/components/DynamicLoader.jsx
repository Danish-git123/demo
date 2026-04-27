import React, { useState, useEffect } from 'react';
import { Loader2 } from 'lucide-react';

const phrases = [
  "Firing up the parser...",
  "Cloning repository...",
  "Analyzing project structure...",
  "Running AST extraction...",
  "Take a minute, this is a deep dive...",
  "Buidling node relationships...",
  "Almost there..."
];

export default function DynamicLoader() {
  const [textIndex, setTextIndex] = useState(0);

  useEffect(() => {
    const interval = setInterval(() => {
      setTextIndex((prev) => (prev + 1) % phrases.length);
    }, 2500); // Change phrase every 2.5 seconds
    
    return () => clearInterval(interval);
  }, []);

  return (
    <div className="flex flex-col items-center justify-center space-y-6">
      <div className="relative">
        <div className="absolute inset-0 bg-primary-500 rounded-full blur-xl opacity-20 animate-pulse-slow"></div>
        <Loader2 className="w-12 h-12 text-primary-500 animate-spin relative z-10" />
      </div>
      
      <div className="h-8 overflow-hidden relative w-64 text-center">
        <p key={textIndex} className="text-gray-300 font-medium font-mono animate-fade-in-up absolute w-full inset-0">
          {phrases[textIndex]}
        </p>
      </div>
    </div>
  );
}

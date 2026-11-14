import type { Config } from 'tailwindcss';
import defaultTheme from 'tailwindcss/defaultTheme';

const config: Config = {
  darkMode: 'class',
  content: [
    './src/pages/**/*.{js,ts,jsx,tsx,mdx}',
    './src/components/**/*.{js,ts,jsx,tsx,mdx}',
    './src/app/**/*.{js,ts,jsx,tsx,mdx}',
  ],
  theme: {
    extend: {
      screens: {
        'mobile-landscape': {
          raw: '(orientation: landscape) and (max-height: 700px)',
        },
      },
      fontFamily: {
        primary: ['Inter', ...defaultTheme.fontFamily.sans],
      },
      colors: {
        // ---- Selene 主题色板（网页端与原生端共用同一套取值）----
        // 强调绿 #27AE60、深蓝灰 #2C3E50（标题/正文）、辅助蓝 #3498DB、
        // 警示红 #E74C3C、评分粉 #E91E63。
        // 直接覆盖 tailwind 的 green / blue / primary 三套色阶，
        // 全站原有的 bg-green-500 / text-blue-600 / bg-primary-* 都自动变成 Selene 配色。
        selene: {
          accent: '#27AE60',
          'accent-hover': '#52C77A',
          ink: '#2C3E50',
          muted: '#7F8C8D',
          line: '#BDC3C7',
          paper: '#F8F9FA',
          card: '#FFFFFF',
          night: '#121212',
          'night-card': '#1E1E1E',
          'night-line': '#2C2C2C',
          danger: '#E74C3C',
          info: '#3498DB',
          rating: '#E91E63',
        },
        gray: {
          50: '#f8f9fa',
          100: '#eef1f4',
          200: '#e4e9ee',
          300: '#bdc3c7',
          400: '#95a5a6',
          500: '#7f8c8d',
          600: '#5f7078',
          700: '#3d4f5d',
          800: '#2c3e50',
          900: '#1e1e1e',
          950: '#121212',
        },
        slate: {
          50: '#f8f9fa',
          100: '#eef1f4',
          200: '#e4e9ee',
          300: '#bdc3c7',
          400: '#95a5a6',
          500: '#7f8c8d',
          600: '#5f7078',
          700: '#3d4f5d',
          800: '#2c3e50',
          900: '#1e1e1e',
          950: '#121212',
        },
        neutral: {
          50: '#f8f9fa',
          100: '#eef1f4',
          200: '#e4e9ee',
          300: '#bdc3c7',
          400: '#95a5a6',
          500: '#7f8c8d',
          600: '#5f7078',
          700: '#3d4f5d',
          800: '#2c3e50',
          900: '#1e1e1e',
          950: '#121212',
        },
        green: {
          50: '#eafaf0',
          100: '#d3f3e0',
          200: '#a8e7c2',
          300: '#70d49b',
          400: '#45c07c',
          500: '#27AE60',
          600: '#1f8f4f',
          700: '#1a7340',
          800: '#165c34',
          900: '#114a2a',
          950: '#082f1a',
        },
        emerald: {
          50: '#eafaf0',
          100: '#d3f3e0',
          200: '#a8e7c2',
          300: '#70d49b',
          400: '#45c07c',
          500: '#27AE60',
          600: '#1f8f4f',
          700: '#1a7340',
          800: '#165c34',
          900: '#114a2a',
          950: '#082f1a',
        },
        blue: {
          50: '#eaf5fd',
          100: '#d0eaf9',
          200: '#a3d5f2',
          300: '#6dbde8',
          400: '#46a8de',
          500: '#3498DB',
          600: '#2b81be',
          700: '#256a9c',
          800: '#1f5679',
          900: '#1b4661',
          950: '#112c3d',
        },
        // Selene 强调绿：全站 bg-primary-* / text-primary-* 的主色。
        primary: {
          50: '#eafaf0',
          100: '#d3f3e0',
          200: '#a8e7c2',
          300: '#70d49b',
          400: '#45c07c',
          500: '#27AE60',
          600: '#1f8f4f',
          700: '#1a7340',
          800: '#165c34',
          900: '#114a2a',
        },
        dark: '#222222',
      },
      keyframes: {
        flicker: {
          '0%, 19.999%, 22%, 62.999%, 64%, 64.999%, 70%, 100%': {
            opacity: '0.99',
            filter:
              'drop-shadow(0 0 1px rgba(252, 211, 77)) drop-shadow(0 0 15px rgba(245, 158, 11)) drop-shadow(0 0 1px rgba(252, 211, 77))',
          },
          '20%, 21.999%, 63%, 63.999%, 65%, 69.999%': {
            opacity: '0.4',
            filter: 'none',
          },
        },
        shimmer: {
          '0%': {
            backgroundPosition: '-700px 0',
          },
          '100%': {
            backgroundPosition: '700px 0',
          },
        },
        fadeIn: {
          '0%': { opacity: '0' },
          '100%': { opacity: '1' },
        },
        slideUp: {
          '0%': { transform: 'translateY(10px)', opacity: '0' },
          '100%': { transform: 'translateY(0)', opacity: '1' },
        },
        slideDown: {
          '0%': { transform: 'translateY(-10px)', opacity: '0' },
          '100%': { transform: 'translateY(0)', opacity: '1' },
        },
        slideInFromRight: {
          '0%': { transform: 'translateX(100%)', opacity: '0' },
          '100%': { transform: 'translateX(0)', opacity: '1' },
        },
      },
      animation: {
        flicker: 'flicker 3s linear infinite',
        shimmer: 'shimmer 1.3s linear infinite',
        'fade-in': 'fadeIn 0.3s ease-in-out',
        'slide-up': 'slideUp 0.3s ease-in-out',
        'slide-down': 'slideDown 0.3s ease-in-out',
        'slide-in-from-right': 'slideInFromRight 0.3s ease-out',
      },
      backgroundImage: {
        'gradient-radial': 'radial-gradient(var(--tw-gradient-stops))',
        'gradient-conic':
          'conic-gradient(from 180deg at 50% 50%, var(--tw-gradient-stops))',
      },
    },
  },
  plugins: [require('@tailwindcss/forms'), require('@tailwindcss/typography')],
} satisfies Config;

export default config;

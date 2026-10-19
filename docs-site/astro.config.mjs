import { defineConfig } from 'astro/config';

export default defineConfig({
  site: 'https://danwills.github.io',
  base: '/EnkiShaderEditor/',
  build: {
    assets: '_assets',
  },
});

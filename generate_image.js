#!/usr/bin/env node

/**
 * Surplus Image Generation Script (Default: qwen-edit-uncensored)
 * 
 * Usage:
 *   node generate_image.js "your prompt here" [output_filename.png]
 */

const fs = require('fs');
const path = require('path');

const apiKey = process.env.SURPLUS_API_KEY;
if (!apiKey) {
  console.error('Error: SURPLUS_API_KEY environment variable is not set.');
  process.exit(1);
}

const prompt = process.argv[2];
if (!prompt) {
  console.error('Usage: node generate_image.js "<prompt>" [output_filename.png]');
  process.exit(1);
}

const defaultFilename = `generated_${Date.now()}.png`;
const outputFile = path.resolve(process.argv[3] || defaultFilename);

const MODEL = 'qwen-edit-uncensored';
const ENDPOINT = 'https://api.surplusintelligence.ai/v1/images/generations';

async function generate() {
  console.log(`[+] Submitting prompt to ${MODEL}...`);
  console.log(`[+] Prompt: "${prompt}"`);

  const startTime = Date.now();

  try {
    const response = await fetch(ENDPOINT, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: MODEL,
        prompt: prompt,
        n: 1,
        size: '1024x1024',
      }),
    });

    if (!response.ok) {
      const errorText = await response.text();
      console.error(`[-] API Error (${response.status}): ${errorText}`);
      process.exit(1);
    }

    const data = await response.json();
    const elapsed = ((Date.now() - startTime) / 1000).toFixed(2);

    if (data.data && data.data[0]) {
      const item = data.data[0];

      if (item.b64_json) {
        const buffer = Buffer.from(item.b64_json, 'base64');
        fs.writeFileSync(outputFile, buffer);
        console.log(`[✓] Success! Image saved to: ${outputFile} (${buffer.length.toLocaleString()} bytes in ${elapsed}s)`);
      } else if (item.url) {
        console.log(`[+] Downloading image from URL...`);
        const imgRes = await fetch(item.url);
        const arrayBuf = await imgRes.arrayBuffer();
        fs.writeFileSync(outputFile, Buffer.from(arrayBuf));
        console.log(`[✓] Success! Image downloaded to: ${outputFile} in ${elapsed}s`);
      } else {
        console.error('[-] Unexpected response format:', data);
      }
    } else {
      console.error('[-] No image data returned:', data);
    }
  } catch (err) {
    console.error('[-] Request failed:', err.message);
    process.exit(1);
  }
}

generate();

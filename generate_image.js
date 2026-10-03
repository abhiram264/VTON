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

function isImageBuffer(buffer) {
  return (
    (buffer.length >= 8 && buffer.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) ||
    (buffer.length >= 3 && buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff) ||
    (buffer.length >= 6 && ['GIF87a', 'GIF89a'].includes(buffer.subarray(0, 6).toString('ascii'))) ||
    (buffer.length >= 12 && buffer.subarray(0, 4).toString('ascii') === 'RIFF' && buffer.subarray(8, 12).toString('ascii') === 'WEBP') ||
    (buffer.length >= 2 && buffer.subarray(0, 2).toString('ascii') === 'BM')
  );
}

function decodeBase64Image(value) {
  if (typeof value !== 'string' || value.length === 0 || value.length % 4 !== 0 || !/^[A-Za-z0-9+/]+={0,2}$/.test(value)) {
    throw new Error('invalid or empty base64 image payload');
  }
  const buffer = Buffer.from(value, 'base64');
  if (buffer.length === 0 || !isImageBuffer(buffer)) {
    throw new Error('decoded payload is not an image');
  }
  return buffer;
}

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

      if (Object.prototype.hasOwnProperty.call(item, 'b64_json')) {
        const buffer = decodeBase64Image(item.b64_json);
        fs.writeFileSync(outputFile, buffer);
        console.log(`[✓] Success! Image saved to: ${outputFile} (${buffer.length.toLocaleString()} bytes in ${elapsed}s)`);
      } else if (item.url) {
        console.log(`[+] Downloading image from URL...`);
        const imgRes = await fetch(item.url);
        if (!imgRes.ok) {
          const errorText = await imgRes.text();
          console.error(`[-] Image download error (${imgRes.status}): ${errorText}`);
          process.exit(1);
        }
        const buffer = Buffer.from(await imgRes.arrayBuffer());
        if (!isImageBuffer(buffer)) {
          throw new Error('downloaded payload is not an image');
        }
        fs.writeFileSync(outputFile, buffer);
        console.log(`[✓] Success! Image downloaded to: ${outputFile} in ${elapsed}s`);
      } else {
        console.error('[-] Unexpected response format:', data);
        process.exit(1);
      }
    } else {
      console.error('[-] No image data returned:', data);
      process.exit(1);
    }
  } catch (err) {
    console.error('[-] Request failed:', err.message);
    process.exit(1);
  }
}

generate();

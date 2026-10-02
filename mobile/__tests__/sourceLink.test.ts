import {
  isSafeExternalUrl,
  openSourceLink,
} from '../src/features/export/sourceLink';

describe('source link safety (C16)', () => {
  test.each([
    'https://shop.example.invalid/p/123456',
    'http://shop.example.invalid/p/1',
    '  https://shop.example.invalid/p/1  ',
  ])('accepts an absolute web URL: %s', url => {
    expect(isSafeExternalUrl(url)).toBe(true);
  });

  test.each([
    'javascript:alert(1)',
    'data:text/html;base64,PHNjcmlwdD4=',
    'file:///etc/passwd',
    'content://com.example/secret',
    'intent://scan/#Intent;scheme=zxing;end',
    'mailto:someone@example.invalid',
    'tel:+15550000000',
    'ftp://example.invalid/file',
    '/relative/path',
    'shop.example.invalid/p/1',
    'https://user:pass@shop.example.invalid/p/1',
    'https://',
    '',
    '   ',
  ])('refuses a non-web, relative or credential-bearing target: %s', url => {
    expect(isSafeExternalUrl(url)).toBe(false);
  });

  test('refuses non-string input', () => {
    expect(isSafeExternalUrl(null)).toBe(false);
    expect(isSafeExternalUrl(undefined)).toBe(false);
    expect(isSafeExternalUrl(42)).toBe(false);
    expect(isSafeExternalUrl({url: 'https://x.invalid'})).toBe(false);
  });

  test('does not invoke the OS handler for an unsafe URL', async () => {
    const open = jest.fn(async () => undefined);

    const result = await openSourceLink('javascript:alert(1)', open);

    expect(result).toEqual({opened: false, reason: 'UNSAFE_URL'});
    expect(open).not.toHaveBeenCalled();
  });

  test('opens a safe URL and reports success', async () => {
    const open = jest.fn(async () => undefined);

    const result = await openSourceLink('https://shop.example.invalid/p/9', open);

    expect(result).toEqual({opened: true});
    expect(open).toHaveBeenCalledWith('https://shop.example.invalid/p/9');
  });

  test('reports a handler failure distinctly instead of throwing', async () => {
    const open = jest.fn(async () => {
      throw new Error('no handler');
    });

    const result = await openSourceLink('https://shop.example.invalid/p/9', open);

    expect(result).toEqual({opened: false, reason: 'CANNOT_OPEN'});
  });
});

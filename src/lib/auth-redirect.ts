export function getSafeNextPath(path: string | null): string {
  if (!path?.startsWith("/") || path.startsWith("//") || /[\\\u0000-\u0020\u007f]/.test(path)) {
    return "/";
  }

  return path;
}

function typeSizeInRem(value: string): string {
  return /^\d+(?:\.\d+)?px$/.test(value)
    ? `${Number(value.slice(0, -2)) / 16}rem`
    : value;
}

/** The theme and the generated CSS both read this, so a role converts alike in either. */
export function typeRoleValueInRem(property: string, value: string): string {
  return property === "fontSize" || property === "lineHeight"
    ? typeSizeInRem(value)
    : value;
}

export function typeRolesInRem<
  T extends Record<string, Record<string, string | number>>,
>(
  roles: T,
): {
  [K in keyof T]: {
    [P in keyof T[K]]: T[K][P] extends string ? string : T[K][P];
  };
} {
  return Object.fromEntries(
    Object.entries(roles).map(([name, role]) => [
      name,
      Object.fromEntries(
        Object.entries(role).map(([property, value]) => [
          property,
          typeof value === "string"
            ? typeRoleValueInRem(property, value)
            : value,
        ]),
      ),
    ]),
  ) as {
    [K in keyof T]: {
      [P in keyof T[K]]: T[K][P] extends string ? string : T[K][P];
    };
  };
}

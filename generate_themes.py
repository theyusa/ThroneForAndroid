#!/usr/bin/env python3
"""
Generate M3 Expressive theme variants for the remaining 19 Material colors.
Each theme needs:
- values/colors_m3_{theme}.xml (light)
- values-night/colors_m3_{theme}.xml (dark)
- values/themes_m3_{theme}.xml (light theme)
- values-night/themes_m3_{theme}.xml (dark theme)
"""

import os

# Material color definitions: (name, primary_500, primary_700, primary_100, primary_900, secondary_200, secondary_900)
THEMES = [
    ("red", "#F44336", "#D32F2F", "#FFEBEE", "#B71C1C", "#FFCDD2", "#3E0000"),
    ("pink", "#E91E63", "#C2185B", "#FCE4EC", "#880E4F", "#F8BBD0", "#3D0013"),
    ("purple", "#9C27B0", "#7B1FA2", "#F3E5F5", "#4A148C", "#E1BEE7", "#310042"),
    ("deep_purple", "#673AB7", "#512DA8", "#EDE7F6", "#311B92", "#D1C4E9", "#1A003A"),
    ("indigo", "#3F51B5", "#303F9F", "#E8EAF6", "#1A237E", "#C5CAE9", "#001042"),
    ("blue", "#2196F3", "#1976D2", "#E3F2FD", "#0D47A1", "#BBDEFB", "#00235A"),
    ("light_blue", "#03A9F4", "#0288D1", "#E1F5FE", "#01579B", "#B3E5FC", "#002F4A"),
    ("cyan", "#00BCD4", "#0097A7", "#E0F7FA", "#006064", "#80DEEA", "#00343A"),
    ("teal", "#009688", "#00796B", "#E0F2F1", "#004D40", "#80CBC4", "#002926"),
    ("green", "#4CAF50", "#388E3C", "#E8F5E9", "#1B5E20", "#C8E6C9", "#00330C"),
    ("light_green", "#8BC34A", "#689F38", "#F1F8E9", "#33691E", "#DCEDC8", "#1D3D00"),
    ("lime", "#CDDC39", "#AFB42B", "#F9FBE7", "#827717", "#F0F4C3", "#3F3E00"),
    ("yellow", "#FFEB3B", "#FBC02D", "#FFFDE7", "#F57F17", "#FFF9C4", "#423D00"),
    ("amber", "#FFC107", "#FFA000", "#FFF8E1", "#FF6F00", "#FFECB3", "#422D00"),
    ("orange", "#FF9800", "#F57C00", "#FFF3E0", "#E65100", "#FFE0B2", "#3E2200"),
    ("deep_orange", "#FF5722", "#E64A19", "#FBE9E7", "#BF360C", "#FFCCBC", "#3E0D00"),
    ("brown", "#795548", "#5D4037", "#EFEBE9", "#3E2723", "#D7CCC8", "#1F1210"),
    ("grey", "#9E9E9E", "#616161", "#FAFAFA", "#212121", "#E0E0E0", "#212121"),
    ("blue_grey", "#607D8B", "#455A64", "#ECEFF1", "#263238", "#B0BEC5", "#001F27"),
]

TEMPLATE_COLORS_LIGHT = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- M3 Expressive Color Palette - {theme_name} (Light) -->
    
    <!-- Primary -->
    <color name="m3_{theme}_primary">{primary_500}</color>
    <color name="m3_{theme}_on_primary">#FFFFFF</color>
    <color name="m3_{theme}_primary_container">{primary_100}</color>
    <color name="m3_{theme}_on_primary_container">{primary_900}</color>
    
    <!-- Secondary -->
    <color name="m3_{theme}_secondary">{secondary_200}</color>
    <color name="m3_{theme}_on_secondary">{secondary_900}</color>
    <color name="m3_{theme}_secondary_container">{primary_100}</color>
    <color name="m3_{theme}_on_secondary_container">{primary_900}</color>
    
    <!-- Tertiary -->
    <color name="m3_{theme}_tertiary">#D0BCFF</color>
    <color name="m3_{theme}_on_tertiary">#381E72</color>
    <color name="m3_{theme}_tertiary_container">#EADDFF</color>
    <color name="m3_{theme}_on_tertiary_container">#31106D</color>
    
    <!-- Error -->
    <color name="m3_{theme}_error">@color/m3_sys_error</color>
    <color name="m3_{theme}_on_error">@color/m3_sys_on_error</color>
    <color name="m3_{theme}_error_container">@color/m3_sys_error_container</color>
    <color name="m3_{theme}_on_error_container">@color/m3_sys_on_error_container</color>
    
    <!-- Surface -->
    <color name="m3_{theme}_surface">@color/m3_sys_surface</color>
    <color name="m3_{theme}_on_surface">@color/m3_sys_on_surface</color>
    <color name="m3_{theme}_surface_variant">@color/m3_sys_surface_variant</color>
    <color name="m3_{theme}_on_surface_variant">@color/m3_sys_on_surface_variant</color>
    
    <color name="m3_{theme}_surface_container_lowest">@color/m3_sys_surface_container_lowest</color>
    <color name="m3_{theme}_surface_container_low">@color/m3_sys_surface_container_low</color>
    <color name="m3_{theme}_surface_container">@color/m3_sys_surface_container</color>
    <color name="m3_{theme}_surface_container_high">@color/m3_sys_surface_container_high</color>
    <color name="m3_{theme}_surface_container_highest">@color/m3_sys_surface_container_highest</color>
    
    <!-- Outline -->
    <color name="m3_{theme}_outline">@color/m3_sys_outline</color>
    <color name="m3_{theme}_outline_variant">@color/m3_sys_outline_variant</color>
    
    <!-- Inverse -->
    <color name="m3_{theme}_inverse_surface">@color/m3_sys_inverse_surface</color>
    <color name="m3_{theme}_inverse_on_surface">@color/m3_sys_inverse_on_surface</color>
    <color name="m3_{theme}_inverse_primary">@color/m3_sys_inverse_primary</color>
</resources>
"""

TEMPLATE_COLORS_DARK = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- M3 Expressive Color Palette - {theme_name} (Dark) -->
    
    <!-- Primary -->
    <color name="m3_{theme}_primary">{primary_200}</color>
    <color name="m3_{theme}_on_primary">{primary_900}</color>
    <color name="m3_{theme}_primary_container">{primary_700}</color>
    <color name="m3_{theme}_on_primary_container">{primary_200}</color>
    
    <!-- Secondary -->
    <color name="m3_{theme}_secondary">{secondary_200}</color>
    <color name="m3_{theme}_on_secondary">{secondary_900}</color>
    <color name="m3_{theme}_secondary_container">{primary_700}</color>
    <color name="m3_{theme}_on_secondary_container">{primary_200}</color>
    
    <!-- Tertiary -->
    <color name="m3_{theme}_tertiary">#D0BCFF</color>
    <color name="m3_{theme}_on_tertiary">#31106D</color>
    <color name="m3_{theme}_tertiary_container">#5943A8</color>
    <color name="m3_{theme}_on_tertiary_container">#EADDFF</color>
    
    <!-- Error -->
    <color name="m3_{theme}_error">@color/m3_sys_error</color>
    <color name="m3_{theme}_on_error">@color/m3_sys_on_error</color>
    <color name="m3_{theme}_error_container">@color/m3_sys_error_container</color>
    <color name="m3_{theme}_on_error_container">@color/m3_sys_on_error_container</color>
    
    <!-- Surface -->
    <color name="m3_{theme}_surface">@color/m3_sys_surface</color>
    <color name="m3_{theme}_on_surface">@color/m3_sys_on_surface</color>
    <color name="m3_{theme}_surface_variant">@color/m3_sys_surface_variant</color>
    <color name="m3_{theme}_on_surface_variant">@color/m3_sys_on_surface_variant</color>
    
    <color name="m3_{theme}_surface_container_lowest">@color/m3_sys_surface_container_lowest</color>
    <color name="m3_{theme}_surface_container_low">@color/m3_sys_surface_container_low</color>
    <color name="m3_{theme}_surface_container">@color/m3_sys_surface_container</color>
    <color name="m3_{theme}_surface_container_high">@color/m3_sys_surface_container_high</color>
    <color name="m3_{theme}_surface_container_highest">@color/m3_sys_surface_container_highest</color>
    
    <!-- Outline -->
    <color name="m3_{theme}_outline">@color/m3_sys_outline</color>
    <color name="m3_{theme}_outline_variant">@color/m3_sys_outline_variant</color>
    
    <!-- Inverse -->
    <color name="m3_{theme}_inverse_surface">@color/m3_sys_inverse_surface</color>
    <color name="m3_{theme}_inverse_on_surface">@color/m3_sys_inverse_on_surface</color>
    <color name="m3_{theme}_inverse_primary">@color/m3_sys_inverse_primary</color>
</resources>
"""

TEMPLATE_THEME_LIGHT = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- M3 Expressive Theme Variants - {theme_name} (Light) -->
    
    <style name="Theme.Throne.M3.{theme_pascal}" parent="Theme.Throne.M3">
        <item name="colorPrimary">@color/m3_{theme}_primary</item>
        <item name="colorOnPrimary">@color/m3_{theme}_on_primary</item>
        <item name="colorPrimaryContainer">@color/m3_{theme}_primary_container</item>
        <item name="colorOnPrimaryContainer">@color/m3_{theme}_on_primary_container</item>
        
        <item name="colorSecondary">@color/m3_{theme}_secondary</item>
        <item name="colorOnSecondary">@color/m3_{theme}_on_secondary</item>
        <item name="colorSecondaryContainer">@color/m3_{theme}_secondary_container</item>
        <item name="colorOnSecondaryContainer">@color/m3_{theme}_on_secondary_container</item>
        
        <item name="colorTertiary">@color/m3_{theme}_tertiary</item>
        <item name="colorOnTertiary">@color/m3_{theme}_on_tertiary</item>
        <item name="colorTertiaryContainer">@color/m3_{theme}_tertiary_container</item>
        <item name="colorOnTertiaryContainer">@color/m3_{theme}_on_tertiary_container</item>
        
        <item name="colorSurface">@color/m3_{theme}_surface</item>
        <item name="colorOnSurface">@color/m3_{theme}_on_surface</item>
        <item name="colorSurfaceVariant">@color/m3_{theme}_surface_variant</item>
        <item name="colorOnSurfaceVariant">@color/m3_{theme}_on_surface_variant</item>
        
        <item name="colorSurfaceContainerLowest">@color/m3_{theme}_surface_container_lowest</item>
        <item name="colorSurfaceContainerLow">@color/m3_{theme}_surface_container_low</item>
        <item name="colorSurfaceContainer">@color/m3_{theme}_surface_container</item>
        <item name="colorSurfaceContainerHigh">@color/m3_{theme}_surface_container_high</item>
        <item name="colorSurfaceContainerHighest">@color/m3_{theme}_surface_container_highest</item>
        
        <item name="colorOutline">@color/m3_{theme}_outline</item>
        <item name="colorOutlineVariant">@color/m3_{theme}_outline_variant</item>
    </style>
    
    <style name="Theme.Throne.M3.Dialog.{theme_pascal}" parent="Theme.Throne.M3.Dialog">
        <item name="colorPrimary">@color/m3_{theme}_primary</item>
        <item name="colorOnPrimary">@color/m3_{theme}_on_primary</item>
        <item name="colorPrimaryContainer">@color/m3_{theme}_primary_container</item>
        <item name="colorOnPrimaryContainer">@color/m3_{theme}_on_primary_container</item>
        <item name="colorSurface">@color/m3_{theme}_surface</item>
        <item name="colorOnSurface">@color/m3_{theme}_on_surface</item>
    </style>
</resources>
"""

TEMPLATE_THEME_DARK = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- M3 Expressive Theme Variants - {theme_name} (Dark) -->
    
    <style name="Theme.Throne.M3.{theme_pascal}" parent="Theme.Throne.M3">
        <item name="colorPrimary">@color/m3_{theme}_primary</item>
        <item name="colorOnPrimary">@color/m3_{theme}_on_primary</item>
        <item name="colorPrimaryContainer">@color/m3_{theme}_primary_container</item>
        <item name="colorOnPrimaryContainer">@color/m3_{theme}_on_primary_container</item>
        
        <item name="colorSecondary">@color/m3_{theme}_secondary</item>
        <item name="colorOnSecondary">@color/m3_{theme}_on_secondary</item>
        <item name="colorSecondaryContainer">@color/m3_{theme}_secondary_container</item>
        <item name="colorOnSecondaryContainer">@color/m3_{theme}_on_secondary_container</item>
        
        <item name="colorTertiary">@color/m3_{theme}_tertiary</item>
        <item name="colorOnTertiary">@color/m3_{theme}_on_tertiary</item>
        <item name="colorTertiaryContainer">@color/m3_{theme}_tertiary_container</item>
        <item name="colorOnTertiaryContainer">@color/m3_{theme}_on_tertiary_container</item>
        
        <item name="colorSurface">@color/m3_{theme}_surface</item>
        <item name="colorOnSurface">@color/m3_{theme}_on_surface</item>
        <item name="colorSurfaceVariant">@color/m3_{theme}_surface_variant</item>
        <item name="colorOnSurfaceVariant">@color/m3_{theme}_on_surface_variant</item>
        
        <item name="colorSurfaceContainerLowest">@color/m3_{theme}_surface_container_lowest</item>
        <item name="colorSurfaceContainerLow">@color/m3_{theme}_surface_container_low</item>
        <item name="colorSurfaceContainer">@color/m3_{theme}_surface_container</item>
        <item name="colorSurfaceContainerHigh">@color/m3_{theme}_surface_container_high</item>
        <item name="colorSurfaceContainerHighest">@color/m3_{theme}_surface_container_highest</item>
        
        <item name="colorOutline">@color/m3_{theme}_outline</item>
        <item name="colorOutlineVariant">@color/m3_{theme}_outline_variant</item>
    </style>
    
    <style name="Theme.Throne.M3.Dialog.{theme_pascal}" parent="Theme.Throne.M3.Dialog">
        <item name="colorPrimary">@color/m3_{theme}_primary</item>
        <item name="colorOnPrimary">@color/m3_{theme}_on_primary</item>
        <item name="colorPrimaryContainer">@color/m3_{theme}_primary_container</item>
        <item name="colorOnPrimaryContainer">@color/m3_{theme}_on_primary_container</item>
        <item name="colorSurface">@color/m3_{theme}_surface</item>
        <item name="colorOnSurface">@color/m3_{theme}_on_surface</item>
    </style>
</resources>
"""

def to_pascal_case(s):
    return ''.join(word.capitalize() for word in s.split('_'))

def generate_theme(theme_name, primary_500, primary_700, primary_100, primary_900, secondary_200, secondary_900):
    # Calculate primary_200 (lighter than 500)
    # For simplicity, we'll use the 100 value as 200 equivalent
    primary_200 = primary_100
    
    theme_pascal = to_pascal_case(theme_name)
    
    # Light colors
    light_colors = TEMPLATE_COLORS_LIGHT.format(
        theme=theme_name,
        theme_name=theme_pascal,
        primary_500=primary_500,
        primary_700=primary_700,
        primary_100=primary_100,
        primary_900=primary_900,
        secondary_200=secondary_200,
        secondary_900=secondary_900,
    )
    
    # Dark colors
    dark_colors = TEMPLATE_COLORS_DARK.format(
        theme=theme_name,
        theme_name=theme_pascal,
        primary_200=primary_200,
        primary_700=primary_700,
        primary_900=primary_900,
        secondary_200=secondary_200,
        secondary_900=secondary_900,
    )
    
    # Light theme
    light_theme = TEMPLATE_THEME_LIGHT.format(
        theme=theme_name,
        theme_name=theme_pascal,
        theme_pascal=theme_pascal,
    )
    
    # Dark theme
    dark_theme = TEMPLATE_THEME_DARK.format(
        theme=theme_name,
        theme_name=theme_pascal,
        theme_pascal=theme_pascal,
    )
    
    return {
        f"values/colors_m3_{theme_name}.xml": light_colors,
        f"values-night/colors_m3_{theme_name}.xml": dark_colors,
        f"values/themes_m3_{theme_name}.xml": light_theme,
        f"values-night/themes_m3_{theme_name}.xml": dark_theme,
    }

def main():
    base_dir = "/root/ThroneForAndroid/app/src/main/res"
    
    for theme in THEMES:
        theme_name, primary_500, primary_700, primary_100, primary_900, secondary_200, secondary_900 = theme
        files = generate_theme(theme_name, primary_500, primary_700, primary_100, primary_900, secondary_200, secondary_900)
        
        for rel_path, content in files.items():
            full_path = os.path.join(base_dir, rel_path)
            os.makedirs(os.path.dirname(full_path), exist_ok=True)
            with open(full_path, 'w') as f:
                f.write(content)
            print(f"Generated: {full_path}")

if __name__ == "__main__":
    main()
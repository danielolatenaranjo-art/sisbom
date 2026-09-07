import os
from PIL import Image
from collections import deque

src_path = r"C:\Users\danie\.gemini\antigravity-ide\brain\3307f9fb-2c94-4909-a56c-b55a696c1c5b\media__1779798666406.jpg"
dest_path = r"c:\Users\danie\Desktop\SisBom - Bomberos Placilla OH\APK\app\src\main\res\drawable\logo_sisbom.png"

with Image.open(src_path) as img:
    img = img.convert("RGBA")
    width, height = img.size
    pixels = img.load()
    
    # We will use flood fill to find the background.
    # The background starts at the borders and consists of pixels with max(r,g,b) < 75.
    visited = [[False for _ in range(height)] for _ in range(width)]
    queue = deque()
    
    # Initialize queue with border pixels that are dark
    border_threshold = 75
    for x in range(width):
        for y in [0, height - 1]:
            r, g, b, a = pixels[x, y]
            if max(r, g, b) < border_threshold:
                queue.append((x, y))
                visited[x][y] = True
                
    for y in range(height):
        for x in [0, width - 1]:
            if not visited[x][y]:
                r, g, b, a = pixels[x, y]
                if max(r, g, b) < border_threshold:
                    queue.append((x, y))
                    visited[x][y] = True
                    
    # BFS flood fill
    # Allow slightly higher threshold for connected pixels to handle gradients/vignette
    fill_threshold = 85
    while queue:
        cx, cy = queue.popleft()
        for dx, dy in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
            nx, ny = cx + dx, cy + dy
            if 0 <= nx < width and 0 <= ny < height:
                if not visited[nx][ny]:
                    r, g, b, a = pixels[nx, ny]
                    if max(r, g, b) < fill_threshold:
                        visited[nx][ny] = True
                        queue.append((nx, ny))
                        
    # Apply transparency with a small soft boundary (antialiasing)
    # For any visited pixel, make it transparent.
    # For unvisited pixels, they are part of the logo.
    for x in range(width):
        for y in range(height):
            if visited[x][y]:
                r, g, b, a = pixels[x, y]
                # Soften transition for pixels near the boundary
                # We can check if any neighbor is unvisited
                is_boundary = False
                for dx, dy in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < width and 0 <= ny < height:
                        if not visited[nx][ny]:
                            is_boundary = True
                            break
                if is_boundary:
                    # Semi-transparent for antialiasing
                    pixels[x, y] = (r, g, b, 100)
                else:
                    pixels[x, y] = (r, g, b, 0)
            else:
                # Make sure it's fully opaque
                r, g, b, a = pixels[x, y]
                pixels[x, y] = (r, g, b, 255)
                
    # Crop the empty space
    bbox = img.getbbox()
    if bbox:
        img = img.crop(bbox)
        
    os.makedirs(os.path.dirname(dest_path), exist_ok=True)
    img.save(dest_path, "PNG")
    print(f"Flood fill processed and saved to {dest_path}. Size: {img.size}")

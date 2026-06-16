/* ============================================
   WinZenith Website - Screenshot Carousel
   & Download Counter
   ============================================ */

document.addEventListener('DOMContentLoaded', () => {
    initCarousel();
    loadDownloadCount();
});

/* --- Screenshot Carousel --- */
function initCarousel() {
    const slides = document.querySelectorAll('.carousel-slide');
    const dots = document.querySelectorAll('.carousel-dot');
    let currentIndex = 0;
    let autoPlayInterval = null;

    function goToSlide(index) {
        slides[currentIndex].classList.remove('active');
        dots[currentIndex].classList.remove('active');
        currentIndex = index;
        slides[currentIndex].classList.add('active');
        dots[currentIndex].classList.add('active');
    }

    function nextSlide() {
        goToSlide((currentIndex + 1) % slides.length);
    }

    function startAutoPlay() {
        autoPlayInterval = setInterval(nextSlide, 5000);
    }

    function stopAutoPlay() {
        clearInterval(autoPlayInterval);
    }

    dots.forEach((dot, index) => {
        dot.addEventListener('click', () => {
            stopAutoPlay();
            goToSlide(index);
            startAutoPlay();
        });
    });

    const carousel = document.querySelector('.carousel');
    if (carousel) {
        carousel.addEventListener('mouseenter', stopAutoPlay);
        carousel.addEventListener('mouseleave', startAutoPlay);
    }

    startAutoPlay();
}

/* --- Download Counter via GitHub API --- */
async function loadDownloadCount() {
    const REPO = 'WinZenith/winzenith.github.io';
    const API_URL = `https://api.github.com/repos/${REPO}/releases`;

    try {
        const response = await fetch(API_URL);
        if (!response.ok) return;

        const releases = await response.json();
        let totalDownloads = 0;

        releases.forEach(release => {
            release.assets.forEach(asset => {
                totalDownloads += asset.download_count || 0;
            });
        });

        const countElements = document.querySelectorAll('.download-count-number');
        countElements.forEach(el => {
            el.textContent = formatNumber(totalDownloads);
        });
    } catch (err) {
        // Silently fail - counter is optional
        console.log('Could not load download count:', err);
    }
}

function formatNumber(num) {
    if (num >= 1000) {
        return num.toLocaleString();
    }
    return num.toString();
}

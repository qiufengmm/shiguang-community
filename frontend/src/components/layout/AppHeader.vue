<template>
  <!-- header 本身保持全宽，添加白色背景和阴影 -->
  <div class="flex items-center ">
    <header class="fixed top-0 z-30 w-full px-5 2xl:px-24 h-[72px] bg-white">
    <!-- 内容区域使用容器宽度限制 -->
    <div class="h-full mx-auto flex items-center relative">
      <!-- logo 容器，与左侧栏对齐 -->
      <div class="pl-10">
        <a href="/" class="flex-shrink-0 flex items-center gap-2 select-none no-underline" aria-label="拾光社区首页">
          <svg class="h-8 w-8 shrink-0" viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg" role="img" aria-hidden="true">
            <rect width="32" height="32" rx="9" fill="#FF2442"/>
            <circle cx="16" cy="16" r="7.2" stroke="#FFFFFF" stroke-width="2.2" stroke-linecap="round" stroke-dasharray="36 9.5" transform="rotate(-45 16 16)"/>
            <circle cx="16" cy="16" r="2.4" fill="#FFFFFF"/>
          </svg>
          <span class="text-[19px] font-semibold text-gray-900 tracking-wide whitespace-nowrap">拾光社区</span>
        </a>
      </div>
      <div class="ml-3 text-[#999] text-xs">由<a href="https://www.quanxiaoha.com/column/" target="_blank" class=" hover:text-gray-600">《犬小哈实战专栏》</a>开发</div>
      <!-- 搜索框容器 -->
      <div class="flex justify-center grow">
        <div class="relative">
          <input 
            v-model="searchKeyword"
            type="text" 
            placeholder="搜索拾光社区"
            class="w-[480px] h-10 px-4 py-2 rounded-full bg-[#f6f6f6] focus:outline-none text-sm caret-[#ff2442]"
            @keyup.enter="handleSearch"
          />
          <div class="absolute right-3 top-1/2 -translate-y-1/2 flex items-center gap-2">
            <!-- 删除按钮 -->
            <button 
              v-if="searchKeyword"
              class="w-5 h-5 flex items-center justify-center text-gray-500 hover:text-gray-600"
              @click="searchKeyword = ''"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                <path d="M18 6L6 18M6 6l12 12" stroke-width="2" stroke-linecap="round"/>
              </svg>
            </button>
            <button class="cursor-pointer" @click="handleSearch">
              <!-- 搜索图标 -->
              <svg class="w-5 h-5 text-gray-500 hover:text-gray-600" viewBox="0 0 24 24" fill="none">
                <path d="M21 21L16.65 16.65M19 11C19 15.4183 15.4183 19 11 19C6.58172 19 3 15.4183 3 11C3 6.58172 6.58172 3 11 3C15.4183 3 19 6.58172 19 11Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </button>
          </div>
        </div>
      </div>
      <!-- 右侧按钮容器 -->
      <div class="flex items-center">
        <span class="cursor-not-allowed text-gray-600 px-5 py-2.5 rounded-full hover:bg-gray-100 hover:text-gray-800">创作中心</span>
        <a href="https://www.quanxiaoha.com/column/" target="_blank" class="text-gray-600 px-5 py-2.5 rounded-full hover:bg-gray-100 hover:text-gray-800">实战专栏</a>
      </div>
    </div>
  </header>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()
const searchKeyword = ref(route.query.keyword || '')

// 处理搜索
const handleSearch = () => {
  if (!searchKeyword.value.trim()) return
  router.push({
    name: 'Search',
    query: { keyword: searchKeyword.value.trim() }
  })
}
</script>

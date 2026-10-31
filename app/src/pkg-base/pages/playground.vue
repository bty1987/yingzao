<template>
  <view>
    <view class="card">
      <view class="sec-title yz-display">基础组件示范</view>
      <text class="cell-sub">docs/15 R12~R13 移动端组件基建验收页(分包 pkg-base):组件真实接线上 behind,非静态稿。</text>
    </view>

    <!-- 宫格 -->
    <view class="card">
      <view class="sec-title">宫格 yz-grid</view>
      <yz-grid :items="gridItems" :columns="4" @tap="onGridTap" />
    </view>

    <!-- 时间轴 -->
    <view class="card">
      <view class="sec-title">时间轴 yz-timeline</view>
      <yz-timeline :items="timelineItems" />
    </view>

    <!-- 搜索联想:人员(本地过滤) -->
    <view class="card">
      <view class="sec-title">搜索联想 · 人员(本地过滤)</view>
      <text class="cell-sub">GET /api/users 全量 + 前端过滤(组织内小数据源)</text>
      <view class="demo">
        <yz-search-select
          v-model="pickedUser"
          placeholder="选择本组织人员"
          :options="userOptions"
          local
          label-key="display_name"
          value-key="id"
          display-key="display_name"
        />
        <text v-if="pickedUser" class="picked">已选:id={{ pickedUser.id }},{{ pickedUser.display_name }}</text>
      </view>
    </view>

    <!-- 搜索联想:楼盘(远程) -->
    <view class="card">
      <view class="sec-title">搜索联想 · 楼盘(远程)</view>
      <text class="cell-sub">GET /api/customerprofile/villages?keyword= 防抖 300ms</text>
      <view class="demo">
        <yz-search-select
          v-model="pickedVillage"
          placeholder="输入楼盘名搜索"
          :fetch="searchVillages"
          label-key="name"
          sub-key="proper_name"
          value-key="id"
        />
        <text v-if="pickedVillage" class="picked">已选:id={{ pickedVillage.id }},{{ pickedVillage.name }}</text>
      </view>
    </view>

    <!-- 搜索联想:商品(远程) -->
    <view class="card">
      <view class="sec-title">搜索联想 · 商品(远程)</view>
      <text class="cell-sub">GET /api/basedata/goods?keyword=(name/no/拼音/条码)</text>
      <view class="demo">
        <yz-search-select
          v-model="pickedGoods"
          placeholder="输入商品名/条码搜索"
          :fetch="searchGoods"
          label-key="name"
          sub-key="sub"
          value-key="id"
        />
        <text v-if="pickedGoods" class="picked">已选:id={{ pickedGoods.id }},{{ pickedGoods.name }}</text>
      </view>
    </view>

    <!-- 图片:编辑态 + 只读态成对 -->
    <view class="card">
      <view class="sec-title">图片上传(编辑态)+ 九宫格(只读态)</view>
      <text class="cell-sub">yz-upload-image 直传 F1 后,id 数组喂给 yz-image-grid 回显;点图全屏预览</text>
      <view class="demo">
        <yz-upload-image v-model="demoFileIds" biz-type="playground" />
      </view>
      <view class="demo">
        <yz-image-grid :file-ids="demoFileIds" :cell="140" />
      </view>
    </view>

    <!-- 扫码 -->
    <view class="card">
      <view class="sec-title">扫码 yz-scan</view>
      <text class="cell-sub">小程序/APP 原生扫码;H5 显式降级提示(请手动输入)</text>
      <view class="demo">
        <button class="btn-mini" @click="onScan">扫一扫</button>
        <text v-if="scanResult" class="picked">码值:{{ scanResult }}</text>
      </view>
    </view>
  </view>
</template>

<script>
// 组件示范页(docs/15 R12~R13):分包 pkg-base 承载,主包体积零增长;
// 三类联想数据源全部真实接线(人员本地过滤/楼盘商品远程),图片两态组件联动演示。
// 项目未配 easycom(pages.json 无该段),分包页引用主包组件须逐个显式 import。
import yzGrid from '../../components/yz-grid.vue'
import yzTimeline from '../../components/yz-timeline.vue'
import yzSearchSelect from '../../components/yz-search-select.vue'
import yzUploadImage from '../../components/yz-upload-image.vue'
import yzImageGrid from '../../components/yz-image-grid.vue'
import { listOrgUsers, pageVillages, pageGoods } from '../../api'
import { scanCode } from '../../utils/yz-scan'

export default {
  components: {
    yzGrid,
    yzTimeline,
    yzSearchSelect,
    yzUploadImage,
    yzImageGrid,
  },
  data() {
    return {
      gridItems: [
        { key: 'lead', icon: '客', title: '登记客源' },
        { key: 'work', icon: '工', title: '我的工地', sub: 'R17' },
        { key: 'scan', icon: '扫', title: '扫一扫' },
        { key: 'more', icon: '全', title: '全部应用', disabled: true },
      ],
      timelineItems: [
        { title: '放线成功', time: '10-28 09:12', desc: '施工开工,排期节点生成', tone: 'moss' },
        { title: '水电验收', time: '10-30 14:00', desc: '待客户确认', tone: 'ochre' },
        { title: '泥木进场', time: '—', desc: '前置节点未完成', tone: 'ink' },
        { title: '整改单', time: '10-31 10:20', desc: '水电返工一处,已派单', tone: 'seal' },
      ],
      userOptions: [],
      pickedUser: null,
      pickedVillage: null,
      pickedGoods: null,
      demoFileIds: [],
      scanResult: '',
    }
  },
  onShow() {
    if (!this.userOptions.length) this.loadUsers()
  },
  methods: {
    go(url) {
      uni.navigateTo({ url })
    },
    async loadUsers() {
      try {
        const res = await listOrgUsers()
        this.userOptions = res.data || []
      } catch (e) {
        // 示范页静默:人员联想留空态
      }
    },
    async searchVillages(keyword) {
      return pageVillages({ keyword, page: 1, size: 20 })
    },
    async searchGoods(keyword) {
      const res = await pageGoods({ keyword, page: 1, size: 20 })
      const rows = (res.data && res.data.rows) || []
      // sub 展示单位与售价(分转元展示)
      rows.forEach((r) => {
        r.sub = (r.unit || '') + (r.selling != null ? ' ¥' + (r.selling / 1000).toFixed(2) : '')
      })
      return res
    },
    onGridTap(item) {
      if (item.key === 'scan') return this.onScan()
      uni.showToast({ title: '宫格点击:' + item.title, icon: 'none' })
    },
    async onScan() {
      try {
        this.scanResult = await scanCode()
      } catch (e) {
        // H5 降级提示已在封装内 toast;取消扫码静默
      }
    },
  },
}
</script>

<style scoped>
.sec-title {
  font-size: 30rpx;
  font-weight: 600;
  color: var(--yz-ink);
  margin-bottom: 8rpx;
}
.demo { margin-top: 20rpx; }
.picked { display: block; margin-top: 12rpx; font-size: 24rpx; color: var(--yz-moss); }
</style>

# frozen_string_literal: true

source "https://rubygems.org"

gem 'fastlane', '~> 2', '>= 2.198.0'
gem 'nokogiri'

plugins_path = File.join(File.dirname(__FILE__), 'fastlane', 'Pluginfile')
eval_gemfile(plugins_path) if File.exist?(plugins_path)
